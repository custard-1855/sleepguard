package dev.sleepguard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import java.time.LocalDateTime

/**
 * 画面ロック用のユーザー補助サービス。システムが常に接続しておくので、常駐の通知なしで動き続けられる。
 * ここで22時〜6時は毎分(と画面点灯・ロック解除時に)時間割を評価し、通知と画面ロックを行う。
 * 再起動やアプリの更新のあともシステムが接続し直す。画面の内容は読まず、通信にも一切関与しない。
 * 画面の切り替えのイベントからは、前面にあるアプリの名前だけを読む(「音声を聴く」で選んだアプリや時計のアプリを使っているかの判定のため)。
 */
class LockService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var state: State
    private lateinit var guard: Guard

    private val ticker = Runnable { tick() }

    /** 画面点灯・ロック解除と、時刻・タイムゾーンの変更で評価し直す。 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = tick()
    }

    override fun onServiceConnected() {
        instance = this
        state = State(this)
        guard = newGuard()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, f)
        main.post(ticker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        main.removeCallbacks(ticker)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    /**
     * 前面のアプリが変わったら評価し直す。通知シェード・キーボード・SleepGuard 自身は切り替えとみなさない。
     * ホーム画面のアプリも見ない。履歴の画面もホーム画面のアプリが出すので、見ると音声や時計のアプリを履歴から閉じられない。
     * ホーム画面からほかのアプリを開けば、そのアプリに移ったとみなす。
     * 画面全体を覆わないウィンドウも見ない。ランチャーはアプリを開いたあとにも部品のウィンドウのイベントを出すので、
     * それを拾うと、開いたアプリが前面にあるのにランチャーに移ったとみなしてしまう。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !event.isFullScreen) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == state.foreground || pkg == packageName || pkg == "com.android.systemui") return
        if (getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == pkg }) return
        if (pkg == homeApp()) return
        state.foreground = pkg
        tick()
    }
    override fun onInterrupt() {}

    private fun homeApp(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    /** 「開始」「停止」のあと。外出中の通知の表示を合わせ直すため、Guard を作り直して評価する。 */
    private fun restart() {
        guard = newGuard()
        tick()
    }

    /**
     * Guard は外出中の通知を出していない前提で始まるので、更新前から残っている通知を消しておく。
     * 外出中なら最初の tick で出し直される。
     */
    private fun newGuard(): Guard {
        Notifier(this).showStatus(false)
        return Guard(state, AppEffects(this))
    }

    private fun tick() {
        // 時刻は端末のタイムゾーン(現地時間)
        val now = LocalDateTime.now()
        if (state.enabled) guard.tick(now, System.currentTimeMillis(), ::readDevice)
        // 次の評価を組み直す。Handler の時計はスリープ中に進まないので遅れることがあるが、
        // 使い始めれば画面点灯・ロック解除でここに来て組み直される
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, Schedule.untilNextTick(now).toMillis())
    }

    private fun readDevice(): Device {
        val audioMode = getSystemService(AudioManager::class.java).mode
        return Device(
            screenOn = getSystemService(PowerManager::class.java).isInteractive,
            keyguardLocked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
            // 着信中・通話中(電話アプリ以外の音声通話も含む)
            inCall = audioMode == AudioManager.MODE_RINGTONE ||
                audioMode == AudioManager.MODE_IN_CALL ||
                audioMode == AudioManager.MODE_IN_COMMUNICATION,
            foreground = state.foreground,
            gateShown = GateActivity.shown,
            clockApp = GateActivity.clockApp(this),
        )
    }

    companion object {
        @Volatile private var instance: LockService? = null
        val enabled get() = instance != null
        fun lock(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /** 確認画面を開く。ユーザー補助サービスは、バックグラウンドからの Activity 起動の制限の例外になっている。 */
        fun ask() {
            val s = instance ?: return
            s.startActivity(Intent(s, GateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** 「開始」「停止」。停止中は評価せず、外出中の通知も消す。 */
        fun setRunning(ctx: Context, on: Boolean) {
            State(ctx).enabled = on
            if (instance == null) Notifier(ctx).showStatus(false) else instance?.restart()
        }
    }
}

/** Guard の評価結果を、通知と画面ロックで起こす。 */
class AppEffects(ctx: Context) : Effects {
    private val notifier = Notifier(ctx)
    private val state = State(ctx)
    override val canLock get() = LockService.enabled
    override fun post(title: String, text: String) = notifier.post(title, text)
    override fun bath() = notifier.bath()
    override fun cancelBath() = notifier.cancelBath()
    override fun lock() { LockService.lock() }
    override fun ask() = LockService.ask()
    // 「停止」中は外出中の通知を出さない
    override fun status(outing: Boolean) { if (state.enabled) notifier.showStatus(outing) }
}
