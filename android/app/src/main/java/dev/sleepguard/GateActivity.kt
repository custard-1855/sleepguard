package dev.sleepguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDateTime

/**
 * ロックの対象になったとき、ロックする代わりに出す確認画面。
 * 10秒操作がないか、戻る・ホーム・履歴で離れたらロックする。
 */
class GateActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val guard by lazy { Guard(State(this), AppEffects(this)) }
    private lateinit var col: LinearLayout

    /** ロック以外で閉じるとき true。onStop でロックしないようにする。 */
    private var resolved = false

    private var idleArmed = false
    private val idle = Runnable { lockAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
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
        setContentView(col)
        showChoice()
    }

    override fun onStart() {
        super.onStart()
        shown = true
        guard.gateOpened()
    }

    override fun onStop() {
        super.onStop()
        shown = false
        main.removeCallbacksAndMessages(null)
        if (!resolved) lockAndFinish()
    }

    override fun onUserInteraction() {
        if (idleArmed) armIdle()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = lockAndFinish()

    private fun page(title: String, text: String) {
        col.removeAllViews()
        add(TextView(this).apply { this.text = title; textSize = 24f })
        add(TextView(this).apply { this.text = text; textSize = 15f })
    }

    private fun add(v: android.view.View) = col.addView(
        v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
    )

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }.also { add(it) }

    private fun showChoice() {
        page("もう寝る時間です", "画面をロックします。")
        button("ロックする") { lockAndFinish() }
        button("緊急で使う") { showConfirm() }
        val state = State(this)
        val sound = state.soundApp.takeIf { it != "" }?.let { packageManager.getLaunchIntentForPackage(it) }
        if (sound != null) {
            // 開いたアプリが前面にある間はロックしない(Guard が前面のアプリで判定する)
            button("音声を聴く") { open(sound) }
        }
        // 時計のアプリが前面にある間もロックしない。アラームの一覧の intent は SET_ALARM の権限を求める機種があるので、
        // ホーム画面から開くときと同じ intent で開く
        val clock = clockApp(this).takeIf { it != "" }?.let { packageManager.getLaunchIntentForPackage(it) }
        if (clock != null) button("アラームを設定") { open(clock) }
        armIdle()
    }

    /** 音声か時計のアプリを開く。開けなければロックする。ここで落ちると、ユーザー補助サービスごと止まってしまう。 */
    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            return lockAndFinish()
        }
        resolved = true
        finish()
    }

    private fun showConfirm() {
        page(
            "本当に緊急ですか?",
            "朝まで待てないことだけに使ってください。110・119 は、ロック画面の「緊急通報」から待たずにかけられます。",
        )
        button("やっぱり寝る") { lockAndFinish() }
        button("緊急です") { showCountdown() }
        armIdle()
    }

    /** 30秒待ってから解除できるようにする。待っている間は操作がなくてもロックしない。 */
    private fun showCountdown() {
        disarmIdle()
        page("", "解除すると、30分はロックしません。")
        val title = col.getChildAt(0) as TextView
        button("やっぱり寝る") { lockAndFinish() }
        val unlock = button("解除する") {
            guard.emergency(LocalDateTime.now(), System.currentTimeMillis())
            resolved = true
            finish()
        }.apply { isEnabled = false }
        val until = System.currentTimeMillis() + Schedule.EMERGENCY_WAIT_MS
        val count = object : Runnable {
            override fun run() {
                val left = (until - System.currentTimeMillis() + 999) / 1000
                if (left > 0) {
                    title.text = "${left}秒後に解除できます"
                    main.postDelayed(this, 1000)
                } else {
                    title.text = "解除できます"
                    unlock.isEnabled = true
                    armIdle()
                }
            }
        }
        count.run()
    }

    private fun armIdle() {
        idleArmed = true
        main.removeCallbacks(idle)
        main.postDelayed(idle, Schedule.GATE_IDLE_MS)
    }

    private fun disarmIdle() {
        idleArmed = false
        main.removeCallbacks(idle)
    }

    private fun lockAndFinish() {
        if (resolved) return
        resolved = true
        guard.lockNow()
        finish()
    }

    companion object {
        /** 確認画面を出しているかどうか。LockService が tick で読む。 */
        @Volatile var shown = false

        /** アラームの一覧を開く、端末の標準の時計のアプリ。ないか、一つに決まっていなければ ""。 */
        fun clockApp(ctx: Context): String {
            val alarms = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            val pkg = ctx.packageManager.resolveActivity(alarms, 0)?.activityInfo?.packageName ?: return ""
            // 時計のアプリが複数あって標準が決まっていないと、選択画面(android)になる
            return if (pkg == "android") "" else pkg
        }
    }
}
