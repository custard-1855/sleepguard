package dev.sleepguard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDateTime

/** 初期設定と状態確認の画面。 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var goOutButton: Button
    private lateinit var comeHomeButton: Button
    private lateinit var soundButton: Button
    private lateinit var stopButton: Button

    private val main = Handler(Looper.getMainLooper())
    private val stopConfirm = StopConfirm()

    /** 出している外出・停止の確認。画面を離れたら閉じて、最初からやり直しにする。 */
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun add(v: android.view.View) = col.addView(
            v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad / 2 }
        )
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }.also { add(it) }

        add(TextView(this).apply { text = "SleepGuard"; textSize = 24f })
        statusView = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad) }
        add(statusView)

        button("1. 通知を許可") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        button("2. ユーザー補助を有効にする") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("3. 開始") { LockService.setRunning(this, true); refresh() }
        button("今夜は入浴済み") { BathDoneReceiver.markBathDone(this); refresh() }
        goOutButton = button("今夜は外出") { confirmGoOut() }
        comeHomeButton = button("帰宅した") { OutingReceiver.comeHome(this); refresh() }
        soundButton = button("音声のアプリを選ぶ") { chooseSoundApp() }
        stopButton = button("停止") { confirmStop() }

        add(TextView(this).apply {
            textSize = 13f
            text = """
                22時〜 使用中は15分おきに入浴の通知
                0時 寝る準備の通知、0:50 予告
                1〜6時 使っていたら通知し、10分後に確認画面を出してロック
                確認画面 緊急解除(30分)、音声を聴く(一晩1回)
                外出中 入浴の通知・予告・ロックなし
                外出と停止は確認してから。1〜6時は押せない
                1時以降に帰宅したら2時からロック
                緊急時 電源ボタン5回で緊急SOS(端末の設定で有効に)
            """.trimIndent()
        })

        // targetSdk 35 以降は画面がステータスバーとナビゲーションバーの下まで広がるので、その分を空ける
        col.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(pad, top + pad, pad, bottom + pad)
            insets
        }
        setContentView(ScrollView(this).apply { addView(col) })
        // 背景が明るいので、ステータスバーとナビゲーションバーのアイコンを暗い色にする
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 入浴の通知の「外出中」から開いたら、外出の確認を出す。 */
    private fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_GO_OUT) confirmGoOut()
    }

    override fun onStop() {
        super.onStop()
        closeDialog()
    }

    private fun closeDialog() {
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
        stopConfirm.cancel()
    }

    /** 10秒待ってから「外出する」を押せるようにする。 */
    private fun confirmGoOut() {
        closeDialog()
        val now = LocalDateTime.now()
        if (!Schedule.canGoOut(now) || State(this).outing(Schedule.nightOf(now))) return
        val d = AlertDialog.Builder(this)
            .setTitle("今夜は外出しますか?")
            .setMessage("今夜は入浴の通知・0:50の予告・1時のロックが止まります。")
            .setNegativeButton("やめる", null)
            .setPositiveButton("外出する") { _, _ -> OutingReceiver.goOut(this); refresh() }
            .show()
        dialog = d
        val go = d.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        val until = System.currentTimeMillis() + Schedule.GO_OUT_WAIT_MS
        val count = object : Runnable {
            override fun run() {
                val left = (until - System.currentTimeMillis() + 999) / 1000
                if (left > 0) {
                    go.text = "あと${left}秒"
                    main.postDelayed(this, 1000)
                } else {
                    go.text = "外出する"
                    go.isEnabled = true
                }
            }
        }
        count.run()
    }

    /**
     * 文言を変えて3回聞く。同じ場所の連打で抜けられないよう「停止する」の左右を毎回入れ替える。
     * OEM がダイアログのボタンを並べ替えることがあるので、ボタンは自前で並べる。
     */
    private fun confirmStop() {
        dialog?.dismiss()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(this).apply { setPadding(pad, 0, pad, 0) }
        val d = AlertDialog.Builder(this)
            .setTitle("停止")
            .setMessage(stopConfirm.message)
            .setView(row)
            .setOnCancelListener { stopConfirm.cancel() }
            .create()
        fun add(label: String, onClick: () -> Unit) = row.addView(
            Button(this).apply {
                text = label; isAllCaps = false; setOnClickListener { d.dismiss(); onClick() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val stop = { onStopPressed() }
        val cancel = { stopConfirm.cancel() }
        if (stopConfirm.stopOnLeft) {
            add("停止する", stop); add("やめる", cancel)
        } else {
            add("やめる", cancel); add("停止する", stop)
        }
        dialog = d
        d.show()
    }

    private fun onStopPressed() {
        when (stopConfirm.stop(LocalDateTime.now())) {
            StopConfirm.Result.NEXT -> confirmStop()
            StopConfirm.Result.STOP -> { LockService.setRunning(this, false); refresh() }
            StopConfirm.Result.TOO_LATE -> {
                AlertDialog.Builder(this).setMessage("1時を過ぎたので停止できません。").setPositiveButton("OK", null)
                    .show().also { dialog = it }
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 「音声を聴く」で開くアプリを、ホーム画面のアプリの一覧から選ぶ。 */
    private fun chooseSoundApp() {
        val apps = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
        AlertDialog.Builder(this)
            .setTitle("音声を聴くで開くアプリ")
            .setItems((listOf("選ばない") + apps.map { it.second }).toTypedArray()) { _, i ->
                State(this).soundApp = if (i == 0) "" else apps[i - 1].first
                refresh()
            }
            .show()
    }

    /** "2026-10-24 2:14 3:05" を "10/24の夜 2:14, 3:05" にする。 */
    private fun emergencyText(log: String): String {
        if (log == "") return "—"
        val parts = log.split(" ")
        val night = java.time.LocalDate.parse(parts[0])
        return "${night.monthValue}/${night.dayOfMonth}の夜 ${parts.drop(1).joinToString(", ")}"
    }

    private fun refresh() {
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val state = State(this)
        val now = LocalDateTime.now()
        val night = Schedule.nightOf(now)
        val bath = state.bathDone(night)
        val outing = state.outing(night)
        goOutButton.isEnabled = !outing && Schedule.canGoOut(now)
        comeHomeButton.isEnabled = outing
        // 1時〜6時に変えると、その場でロックを抜けられてしまうので変えさせない
        soundButton.isEnabled = !Schedule.isLate(now)
        stopButton.isEnabled = state.enabled && Schedule.canStop(now)
        fun mark(b: Boolean) = if (b) "✓" else "—"
        statusView.text = """
            通知の許可  ${mark(notif)}
            ユーザー補助  ${mark(LockService.enabled)}
            開始        ${mark(state.enabled)}
            今夜の入浴  ${if (bath) "済み" else "まだ"}
            今夜の外出  ${if (outing) "外出中" else "—"}
            音声のアプリ  ${if (state.soundApp != "") "選択済み" else "—"}
            緊急解除    ${emergencyText(state.emergencyLog)}
        """.trimIndent()
    }

    companion object {
        const val ACTION_GO_OUT = "dev.sleepguard.GO_OUT"
    }
}
