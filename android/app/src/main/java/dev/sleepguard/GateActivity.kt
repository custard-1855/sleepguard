package dev.sleepguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ロックの対象になったとき、ロックする代わりに出す確認画面。
 * ロックするか、夜中も使えるアプリ(選んだアプリ、電話、時計)を開くかを選ぶ。
 * 10秒操作がないか、戻る・ホーム・履歴で離れたらロックする。
 */
class GateActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val guard by lazy { Guard(State(this), AppEffects(this)) }
    private lateinit var col: LinearLayout

    /** ロック以外で閉じるとき true。onStop でロックしないようにする。 */
    private var resolved = false

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

    override fun onUserInteraction() = armIdle()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = lockAndFinish()

    private fun add(v: android.view.View) = col.addView(
        v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
    )

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }.also { add(it) }

    private fun showChoice() {
        add(TextView(this).apply { text = "もう寝る時間です"; textSize = 24f })
        add(TextView(this).apply { text = "画面をロックします。"; textSize = 15f })
        button("ロックする") { lockAndFinish() }
        // 以下のアプリは、開いたアプリが前面にある間はロックしない(Guard が前面のアプリで判定する)
        val state = State(this)
        for (app in ChosenApp.values()) {
            launch(state.chosen(app))?.let { button(app.open) { open(it) } }
        }
        launch(phoneApp(this))?.let { button("電話をかける") { open(it) } }
        // アラームの一覧の intent は SET_ALARM の権限を求める機種があるので、ホーム画面から開くときと同じ intent で開く
        launch(clockApp(this))?.let { button("アラームを設定") { open(it) } }
        armIdle()
    }

    /** pkg をホーム画面から開くときと同じ intent。pkg が "" か、入っていなければ null。 */
    private fun launch(pkg: String): Intent? =
        pkg.takeIf { it != "" }?.let { packageManager.getLaunchIntentForPackage(it) }

    /** アプリを開く。開けなければロックする。ここで落ちると、ユーザー補助サービスごと止まってしまう。 */
    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            return lockAndFinish()
        }
        resolved = true
        finish()
    }

    private fun armIdle() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, Schedule.GATE_IDLE_MS)
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

        /** 端末の標準の電話のアプリ。なければ ""。 */
        fun phoneApp(ctx: Context): String =
            ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage ?: ""
    }
}
