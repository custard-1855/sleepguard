package dev.sleepguard

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * tick のたびに読み直す端末の状態。
 * foreground は前面にあるアプリのパッケージ名(わからなければ "")、gateShown は確認画面を出しているかどうか。
 * clockApp は端末の標準の時計のアプリのパッケージ名(わからなければ "")。
 */
data class Device(
    val screenOn: Boolean,
    val keyguardLocked: Boolean,
    val inCall: Boolean,
    val foreground: String = "",
    val gateShown: Boolean = false,
    val clockApp: String = "",
)

/** 時間割の評価結果として起こすこと。 */
interface Effects {
    /** 画面ロック用のユーザー補助が有効か。 */
    val canLock: Boolean
    fun post(title: String, text: String)
    fun bath()
    fun cancelBath()
    fun lock()
    /** ロックする代わりに確認画面を出す。 */
    fun ask()
    /** 外出中だけ「帰宅した」ボタンつきの通知を出し、外出中でなければ消す。 */
    fun status(outing: Boolean)
}

/** 時間割を評価して、通知と画面ロックを決める。Android には触らない。 */
class Guard(private val state: State, private val fx: Effects) {

    /** 外出中の通知をいま出しているかどうか。出していない状態で始まる。 */
    private var statusOuting = false

    /** 端末の状態は、使用中かどうかが要る時間帯(22時台と1時以降)にだけ読む。 */
    fun tick(now: LocalDateTime, ms: Long, readDevice: () -> Device) {
        val hm = Schedule.hm(now)
        val night = Schedule.nightOf(now)
        val outing = state.outing(night)
        // 夜が明けて切れたときや、通知のボタン・再起動で表示とずれたときに、外出中の通知を合わせる
        if (outing != statusOuting) showStatus(outing)
        val device by lazy(readDevice)
        // ロック画面を見ているだけのときと通話中は、使っていないとみなす
        val inUse by lazy { device.screenOn && !device.keyguardLocked && !device.inCall }

        // 0時を過ぎたら、出したままの入浴の通知を片付ける
        if (hm < Schedule.BATH_START && state.bathShownNight != "") {
            state.bathShownNight = ""
            fx.cancelBath()
        }

        when {
            hm >= Schedule.BATH_START -> {
                if (!outing && !state.bathDone(night) && inUse &&
                    ms - state.lastBathNotify >= Schedule.BATH_INTERVAL_MS
                ) {
                    state.lastBathNotify = ms
                    state.bathShownNight = night.toString()
                    fx.bath()
                }
            }
            hm >= Schedule.LATE_END -> { /* 日中は何もしない */ }
            hm < Schedule.WARN -> {
                if (state.once("midnight", night))
                    fx.post("0時です", "寝る準備をしましょう。")
            }
            hm < Schedule.LATE_START -> {
                if (!outing && state.once("warn", night))
                    fx.post("まもなく1時です", "1時以降も使っていると、10分後に画面をロックします。")
            }
            outing -> if (inUse && state.once("outingLate", night)) {
                fx.post("外出中でも2時までには寝ましょう", "")
            }
            hm < Schedule.LATE_HOME_START && state.lateHome(night) -> { /* 1時以降に帰宅したら2時までは何もしない */ }
            else -> if (inUse) {
                if (state.lateNight() != night.toString()) {
                    state.startLate(night, ms)
                    fx.post(
                        if (state.lateHome(night)) "2時を過ぎました" else "1時を過ぎました",
                        "10分後もまだ使っていたら画面をロックします。",
                    )
                } else if (ms - state.lateSince() >= Schedule.LOCK_GRACE_MS) {
                    askOrLock(night, ms, device)
                }
            } else if (state.askedAt != 0L) {
                state.askedAt = 0
            }
        }
    }

    /** ロックの対象になったとき。緊急解除中と、音声か時計のアプリが前面にある間は見逃し、それ以外は確認画面を出す。 */
    private fun askOrLock(night: LocalDate, ms: Long, device: Device) {
        if (ms < state.emergencyUntil) return
        val fg = device.foreground
        if (fg != "" && (fg == state.soundApp || fg == device.clockApp)) {
            // 確認画面から音声か時計のアプリに移った。確認画面を出せなかったときのロックはしない
            if (state.askedAt != 0L) state.askedAt = 0
            return
        }
        if (device.gateShown) return
        if (!fx.canLock) {
            if (state.once("cannotLock", night))
                fx.post("画面をロックできません", "アプリを開いて、画面ロック用のユーザー補助を有効にしてください。")
            return
        }
        val asked = state.askedAt
        if (asked == 0L) {
            state.askedAt = ms
            fx.ask()
        } else if (ms - asked >= Schedule.ASK_TIMEOUT_MS) {
            // 確認画面を出せなかった(端末の制限など)ので、普段どおりロックする
            state.askedAt = 0
            lockNow()
        }
    }

    /** 確認画面が開いた。出せなかったときのロックをやめる。 */
    fun gateOpened() {
        state.askedAt = 0
    }

    /** 確認画面の「ロックする」や、確認画面を出せなかったとき。 */
    fun lockNow() {
        fx.post(
            "画面をロックします",
            "もう寝ましょう。緊急時は電源ボタンを素早く5回押すと緊急SOS(要設定)、" +
                "ロック画面の「緊急通報」から110・119にかけられます。",
        )
        fx.lock()
    }

    /** 確認画面の緊急解除。30分ロックせず、使った時刻を記録する。 */
    fun emergency(now: LocalDateTime, ms: Long) {
        state.askedAt = 0
        state.emergencyUntil = ms + Schedule.EMERGENCY_MS
        state.addEmergency(Schedule.nightOf(now), "${now.hour}:%02d".format(now.minute))
    }


    /** 「外出中」「今夜は外出」。その夜だけ外出モードにする。 */
    fun goOut(now: LocalDateTime) {
        if (!Schedule.canGoOut(now)) return
        state.setOuting(Schedule.nightOf(now))
        state.bathShownNight = ""
        fx.cancelBath()
        showStatus(true)
    }

    /** 「帰宅した」。外出モードを切って普段の流れに戻す。 */
    fun comeHome(now: LocalDateTime) {
        val night = Schedule.nightOf(now)
        if (!state.outing(night)) return
        state.clearOuting()
        showStatus(false)
        val hm = Schedule.hm(now)
        if (hm >= Schedule.LATE_START && hm < Schedule.LATE_END) {
            state.setLateHome(night)
            fx.post("おかえりなさい", "2時以降も使っていたら、10分後に画面をロックします。")
        }
    }

    private fun showStatus(outing: Boolean) {
        statusOuting = outing
        fx.status(outing)
    }
}
