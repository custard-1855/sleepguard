package dev.sleepguard

import java.time.LocalDateTime

/** 「停止」の確認の流れ。文言を変えて3回聞き、3回目で停止する。Android には触らない。 */
class StopConfirm {

    /** NEXT は次の確認を出す、TOO_LATE は確認の途中で1時を過ぎたので停止しない。 */
    enum class Result { NEXT, STOP, TOO_LATE }

    private var step = 0

    /** いま出す確認の文言。 */
    val message get() = MESSAGES[step]

    /** いま出す確認で「停止する」を左に置くか。同じ場所の連打で抜けられないよう、確認ごとに入れ替える。 */
    val stopOnLeft get() = step % 2 == 1

    /** 確認の「停止する」。 */
    fun stop(now: LocalDateTime): Result {
        // 0:59 に確認を始めて 1時すぎに押し切る、を防ぐ
        if (!Schedule.canStop(now)) {
            step = 0
            return Result.TOO_LATE
        }
        step++
        return if (step < STEPS) Result.NEXT else Result.STOP
    }

    /** 確認の「やめる」。次に「停止」を押したら最初の確認から聞く。 */
    fun cancel() {
        step = 0
    }

    companion object {
        private val MESSAGES = listOf(
            "停止すると、もう一度「開始」を押すまで通知もロックも動きません。停止しますか?",
            "今夜の見守りも止まります。本当に停止しますか?",
            "本当に本当に? 寝る時刻を守るために入れたアプリです。",
        )
        val STEPS = MESSAGES.size
    }
}
