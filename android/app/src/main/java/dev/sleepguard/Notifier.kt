package dev.sleepguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** 通知まわり。音は鳴らさず、ヘッドアップ表示だけにする。 */
class Notifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, "就寝の促し", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "外出モード", NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(label: String, requestCode: Int, intent: Intent) = Notification.Action.Builder(
        Icon.createWithResource(ctx, R.drawable.ic_moon), label,
        PendingIntent.getBroadcast(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE),
    ).build()

    /** 外出中だけ「帰宅した」ボタンつきの通知を出しておく。外出中でなければ消す。 */
    fun showStatus(outing: Boolean) {
        if (!outing) return nm.cancel(ID_STATUS)
        nm.notify(
            ID_STATUS,
            Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle("外出モード(今夜だけ)")
                .setContentText("帰ったら「帰宅した」を押してください。")
                .setContentIntent(openApp())
                .setOngoing(true)
                .addAction(action("帰宅した", 3, OutingReceiver.intent(ctx, OutingReceiver.ACTION_COME_HOME)))
                .build()
        )
    }

    fun post(title: String, text: String) {
        nm.notify(
            ID_INFO,
            Notification.Builder(ctx, CH_GUARD)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                // ロック中に緊急時の案内を読めるように、ロック画面でも本文を出す
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
    }

    fun bath() {
        val text = "区切りをつけて入りましょう。入浴済みを押すと今夜はもう出ません。外出中なら「外出中」を押してください。"
        nm.notify(
            ID_BATH,
            Notification.Builder(ctx, CH_GUARD)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle("お風呂の時間です")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp())
                .addAction(action("入浴済み", 1, Intent(ctx, BathDoneReceiver::class.java)))
                // 一度押しでは入らないよう、アプリ画面を開いて外出の確認を出す
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(ctx, R.drawable.ic_moon), "外出中",
                        PendingIntent.getActivity(
                            ctx, 2,
                            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_GO_OUT)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).build()
                )
                .build()
        )
    }

    fun cancelBath() = nm.cancel(ID_BATH)

    companion object {
        const val CH_GUARD = "guard"
        const val CH_STATUS = "status"
        const val ID_STATUS = 1
        const val ID_BATH = 2
        const val ID_INFO = 3
    }
}
