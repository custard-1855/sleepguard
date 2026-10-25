package dev.sleepguard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class GuardTest {

    private class MapStore : Store {
        val m = mutableMapOf<String, Any>()
        override fun getString(key: String) = m[key] as String? ?: ""
        override fun putString(key: String, value: String) { m[key] = value }
        override fun getLong(key: String) = m[key] as Long? ?: 0L
        override fun putLong(key: String, value: Long) { m[key] = value }
    }

    /** 通知のタイトルとロックを起きた順に記録する。 */
    private class Recorder : Effects {
        val log = mutableListOf<String>()
        override var canLock = true
        override fun post(title: String, text: String) { log += title }
        override fun bath() { log += "入浴" }
        override fun cancelBath() { log += "入浴を消す" }
        override fun lock() { log += "ロック" }
        override fun ask() { log += "確認画面" }
        override fun status(outing: Boolean) { log += if (outing) "常駐:外出" else "常駐:動作中" }
    }

    private val fx = Recorder()
    private val store = MapStore()
    private val guard = Guard(State(store), fx)

    private val unlocked = Device(screenOn = true, keyguardLocked = false, inCall = false)
    private val lockScreen = Device(screenOn = true, keyguardLocked = true, inCall = false)
    private val ASK = listOf("確認画面")
    private val LOCK = listOf("画面をロックします", "ロック")

    private val AUDIO = "com.example.audio"
    private fun using(app: String) = unlocked.copy(foreground = app)

    init {
        State(store).soundApp = AUDIO
    }

    private var deviceReads = 0

    /** time は "HH:mm"(2026-10-25 の時刻)か、日付つきの "yyyy-MM-ddTHH:mm"。 */
    private fun at(time: String) = LocalDateTime.parse(if ("T" in time) time else "2026-10-25T$time")

    private fun tick(time: String, device: Device): List<String> {
        val now = at(time)
        fx.log.clear()
        guard.tick(now, now.toEpochSecond(ZoneOffset.UTC) * 1000) { deviceReads++; device }
        return fx.log.toList()
    }

    @Test
    fun `日中と0時台は端末の状態を読まない`() {
        tick("12:00", unlocked)
        tick("00:10", unlocked)
        tick("00:55", unlocked)
        assertEquals(0, deviceReads)
    }

    @Test
    fun `1時以降に使っていたら通知し、10分後も使っていたら確認画面を出す`() {
        assertEquals(listOf("1時を過ぎました"), tick("01:00", unlocked))
        assertEquals(emptyList<String>(), tick("01:09", unlocked))
        assertEquals(ASK, tick("01:10", unlocked))
    }

    @Test
    fun `ロック後に時刻を見ようと画面を点けただけではロックしない`() {
        tick("01:00", unlocked)
        tick("01:10", unlocked)
        assertEquals(emptyList<String>(), tick("02:30", lockScreen))
        assertEquals(ASK, tick("02:31", unlocked))
    }

    @Test
    fun `ロック画面を見ただけでは10分の猶予が始まらない`() {
        assertEquals(emptyList<String>(), tick("01:00", lockScreen))
        assertEquals(listOf("1時を過ぎました"), tick("01:30", unlocked))
        assertEquals(emptyList<String>(), tick("01:39", unlocked))
    }

    @Test
    fun `画面ロックが使えないときは、ロックできないことをその夜に一度だけ知らせる`() {
        fx.canLock = false
        tick("01:00", unlocked)
        assertEquals(listOf("画面をロックできません"), tick("01:10", unlocked))
        assertEquals(emptyList<String>(), tick("01:11", unlocked))
    }

    @Test
    fun `入浴の通知は0時を過ぎて最初に一度だけ消す`() {
        assertEquals(listOf("入浴"), tick("2026-10-24T22:00", unlocked))
        assertEquals(listOf("入浴を消す"), tick("01:00", lockScreen))
        assertEquals(emptyList<String>(), tick("01:01", lockScreen))
    }

    @Test
    fun `通話中はロックしない`() {
        tick("01:00", unlocked)
        assertEquals(emptyList<String>(), tick("01:10", unlocked.copy(inCall = true)))
    }

    @Test
    fun `外出中は入浴の通知を出さない`() {
        guard.goOut(at("2026-10-24T21:00"))
        assertEquals(emptyList<String>(), tick("2026-10-24T22:00", unlocked))
    }

    @Test
    fun `外出モードにしたら、出ている入浴の通知を閉じる`() {
        tick("2026-10-24T22:00", unlocked)
        fx.log.clear()
        guard.goOut(at("2026-10-24T22:05"))
        assertEquals(listOf("入浴を消す", "常駐:外出"), fx.log)
        assertEquals(emptyList<String>(), tick("01:00", lockScreen))
    }

    @Test
    fun `外出中は0時の通知と1時以降の「2時までに」だけを出し、ロックしない`() {
        guard.goOut(at("2026-10-24T21:00"))
        assertEquals(listOf("0時です"), tick("00:00", unlocked))
        assertEquals(emptyList<String>(), tick("00:50", unlocked))
        assertEquals(emptyList<String>(), tick("01:00", lockScreen))
        assertEquals(listOf("外出中でも2時までには寝ましょう"), tick("01:05", unlocked))
        assertEquals(emptyList<String>(), tick("01:15", unlocked))
        assertEquals(emptyList<String>(), tick("02:30", unlocked))
    }

    @Test
    fun `朝6時すぎに外出モードにすると、その日の夜に効く`() {
        guard.goOut(at("07:00"))
        assertEquals(emptyList<String>(), tick("22:00", unlocked))
    }

    @Test
    fun `入浴の通知を出したまま朝6時すぎまで tick が来なくても、次の tick で消す`() {
        tick("2026-10-24T22:50", unlocked)
        assertEquals(listOf("入浴を消す"), tick("07:00", unlocked))
    }

    @Test
    fun `外出モードは次の夜には切れている`() {
        guard.goOut(at("2026-10-24T21:00"))
        assertEquals(listOf("常駐:動作中", "入浴"), tick("22:00", unlocked))
        assertEquals(listOf("入浴を消す", "1時を過ぎました"), tick("2026-10-26T01:00", unlocked))
    }

    @Test
    fun `0時台に帰宅すると、普段どおり1時に通知して1時10分にロックする`() {
        guard.goOut(at("2026-10-24T21:00"))
        guard.comeHome(at("00:30"))
        assertEquals(listOf("まもなく1時です"), tick("00:50", unlocked))
        assertEquals(listOf("1時を過ぎました"), tick("01:00", unlocked))
        assertEquals(ASK, tick("01:10", unlocked))
    }

    @Test
    fun `1時台に帰宅すると、2時まではロックせず、2時に通知して2時10分にロックする`() {
        guard.goOut(at("2026-10-24T21:00"))
        fx.log.clear()
        guard.comeHome(at("01:30"))
        assertEquals(listOf("常駐:動作中", "おかえりなさい"), fx.log)
        assertEquals(emptyList<String>(), tick("01:31", unlocked))
        assertEquals(emptyList<String>(), tick("01:59", unlocked))
        assertEquals(listOf("2時を過ぎました"), tick("02:00", unlocked))
        assertEquals(emptyList<String>(), tick("02:09", unlocked))
        assertEquals(ASK, tick("02:10", unlocked))
    }

    @Test
    fun `2時以降に帰宅すると、その時点から通知して10分後にロックする`() {
        guard.goOut(at("2026-10-24T21:00"))
        guard.comeHome(at("02:30"))
        assertEquals(listOf("2時を過ぎました"), tick("02:31", unlocked))
        assertEquals(emptyList<String>(), tick("02:40", unlocked))
        assertEquals(ASK, tick("02:41", unlocked))
    }

    @Test
    fun `外出モードでないときに帰宅したを押しても、ロックは先に延びない`() {
        tick("01:00", unlocked)
        fx.log.clear()
        guard.comeHome(at("01:05"))
        assertEquals(emptyList<String>(), fx.log)
        assertEquals(ASK, tick("01:10", unlocked))
    }

    @Test
    fun `外出モードに入ると常駐の通知を外出中にし、帰宅すると動作中に戻す`() {
        guard.goOut(at("2026-10-24T21:00"))
        assertEquals(listOf("入浴を消す", "常駐:外出"), fx.log)
        fx.log.clear()
        guard.comeHome(at("2026-10-24T23:00"))
        assertEquals(listOf("常駐:動作中"), fx.log)
    }

    @Test
    fun `外出モードが朝6時に切れたら、常駐の通知を動作中に戻す`() {
        guard.goOut(at("2026-10-24T21:00"))
        assertEquals(emptyList<String>(), tick("05:59", lockScreen))
        assertEquals(listOf("常駐:動作中"), tick("06:00", lockScreen))
        assertEquals(emptyList<String>(), tick("06:01", lockScreen))
    }

    @Test
    fun `通知のボタンや再起動で外出モードを知らなかった常駐も、次の tick で常駐の通知を外出中にする`() {
        Guard(State(store), Recorder()).goOut(at("2026-10-24T21:00"))
        assertEquals(listOf("常駐:外出"), tick("2026-10-24T21:01", lockScreen))
        assertEquals(emptyList<String>(), tick("2026-10-24T21:02", lockScreen))
    }

    @Test
    fun `1時から6時までは外出モードに入れない`() {
        tick("01:00", unlocked)
        fx.log.clear()
        guard.goOut(at("01:05"))
        assertEquals(emptyList<String>(), fx.log)
        assertEquals(ASK, tick("01:10", unlocked))
    }

    @Test
    fun `確認画面を出している間は何もしない`() {
        tick("01:00", unlocked)
        tick("01:10", unlocked)
        assertEquals(emptyList<String>(), tick("01:11", unlocked.copy(gateShown = true)))
    }

    @Test
    fun `確認画面を出せなかったら、次の評価でロックする`() {
        tick("01:00", unlocked)
        assertEquals(ASK, tick("01:10", unlocked))
        assertEquals(LOCK, tick("01:11", unlocked))
        assertEquals(ASK, tick("01:12", unlocked))
    }

    @Test
    fun `確認画面が出る前に画面を消したら、次に使ったときにまた確認画面を出す`() {
        tick("01:00", unlocked)
        tick("01:10", unlocked)
        assertEquals(emptyList<String>(), tick("01:11", lockScreen))
        assertEquals(ASK, tick("01:12", unlocked))
    }

    @Test
    fun `確認画面からロックするときは、緊急時の案内つきの通知を出してロックする`() {
        guard.lockNow()
        assertEquals(LOCK, fx.log)
    }

    @Test
    fun `緊急解除すると30分はロックせず、そのあとはまた確認画面を出す`() {
        tick("01:00", unlocked)
        tick("01:10", unlocked)
        guard.emergency(at("01:10"), at("01:10").toEpochSecond(ZoneOffset.UTC) * 1000)
        assertEquals(emptyList<String>(), tick("01:11", unlocked))
        assertEquals(emptyList<String>(), tick("01:39", unlocked))
        assertEquals(ASK, tick("01:40", unlocked))
    }

    @Test
    fun `緊急解除は何度でも使え、使った時刻を最後に使った夜の分だけ記録する`() {
        guard.emergency(at("2026-10-24T01:30"), 0)
        guard.emergency(at("02:14"), 0)
        guard.emergency(at("03:05"), 0)
        assertEquals("2026-10-24 2:14 3:05", State(store).emergencyLog)
    }

    @Test
    fun `音声のアプリが前面にあるあいだは、時間に関係なくロックしない`() {
        tick("01:00", unlocked)
        assertEquals(emptyList<String>(), tick("01:10", using(AUDIO)))
        assertEquals(emptyList<String>(), tick("03:00", using(AUDIO)))
    }

    @Test
    fun `音声のアプリからほかのアプリに移ったら確認画面を出し、戻れば何度でも使える`() {
        tick("01:00", unlocked)
        tick("01:10", using(AUDIO))
        assertEquals(ASK, tick("01:20", using("com.android.launcher")))
        assertEquals(emptyList<String>(), tick("01:21", using(AUDIO).copy(gateShown = true)))
        assertEquals(emptyList<String>(), tick("01:30", using(AUDIO)))
        assertEquals(ASK, tick("01:40", using("com.android.chrome")))
    }

    @Test
    fun `音声のアプリを選んでいなければ、前面のアプリがわからなくても見逃さない`() {
        State(store).soundApp = ""
        tick("01:00", unlocked)
        assertEquals(ASK, tick("01:10", unlocked))
    }

    private val CLOCK = "com.example.clock"
    private fun usingClock(app: String) = using(app).copy(clockApp = CLOCK)

    @Test
    fun `時計のアプリが前面にあるあいだはロックせず、ほかのアプリに移ったら確認画面を出す`() {
        tick("01:00", unlocked)
        assertEquals(emptyList<String>(), tick("01:10", usingClock(CLOCK)))
        assertEquals(emptyList<String>(), tick("03:00", usingClock(CLOCK)))
        assertEquals(ASK, tick("03:01", usingClock("com.android.launcher")))
    }

    @Test
    fun `22時から6時までは毎分0秒すぎに評価し、日中は22時まで空ける`() {
        fun next(time: String) = at(time).plus(Schedule.untilNextTick(at(time))).toString()
        assertEquals("2026-10-25T22:01:00.500", next("22:00:00.500"))
        assertEquals("2026-10-26T00:00:00.500", next("23:59:30"))
        assertEquals("2026-10-25T06:00:00.500", next("05:59:10"))
        assertEquals("2026-10-25T22:00:00.500", next("06:00:00.500"))
        assertEquals("2026-10-25T22:00:00.500", next("21:59:59"))
    }
}
