package dev.sleepguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

/** 通知の「入浴済み」ボタン。 */
class BathDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        markBathDone(ctx)
    }

    companion object {
        fun markBathDone(ctx: Context) {
            State(ctx).setBathDone(Schedule.nightOf(LocalDateTime.now()))
            Notifier(ctx).cancelBath()
        }
    }
}

/** 外出中の通知の「帰宅した」。入浴の通知の「外出中」は、確認を出すためにアプリ画面を開く。 */
class OutingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_COME_HOME) comeHome(ctx)
    }

    companion object {
        const val ACTION_COME_HOME = "dev.sleepguard.COME_HOME"

        fun intent(ctx: Context, action: String) = Intent(ctx, OutingReceiver::class.java).setAction(action)

        fun goOut(ctx: Context) = Guard(State(ctx), AppEffects(ctx)).goOut(LocalDateTime.now())
        fun comeHome(ctx: Context) = Guard(State(ctx), AppEffects(ctx)).comeHome(LocalDateTime.now())
    }
}
