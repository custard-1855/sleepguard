package dev.sleepguard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class StopConfirmTest {

    private fun at(time: String) = LocalDateTime.parse("2026-10-25T$time")

    @Test
    fun `1時から6時までは停止できない`() {
        assertEquals(true, Schedule.canStop(at("00:59")))
        assertEquals(false, Schedule.canStop(at("01:00")))
        assertEquals(false, Schedule.canStop(at("05:59")))
        assertEquals(true, Schedule.canStop(at("06:00")))
    }

    @Test
    fun `3回目の確認で初めて停止する`() {
        val c = StopConfirm()
        assertEquals(StopConfirm.Result.NEXT, c.stop(at("23:00")))
        assertEquals(StopConfirm.Result.NEXT, c.stop(at("23:00")))
        assertEquals(StopConfirm.Result.STOP, c.stop(at("23:00")))
    }

    @Test
    fun `やめると最初の確認からやり直す`() {
        val c = StopConfirm()
        c.stop(at("23:00"))
        c.stop(at("23:00"))
        c.cancel()
        assertEquals(StopConfirm.Result.NEXT, c.stop(at("23:00")))
        assertEquals(StopConfirm.Result.NEXT, c.stop(at("23:00")))
        assertEquals(StopConfirm.Result.STOP, c.stop(at("23:00")))
    }

    @Test
    fun `確認のたびに停止するの位置が直前と逆になる`() {
        val c = StopConfirm()
        val first = c.stopOnLeft
        c.stop(at("23:00"))
        val second = c.stopOnLeft
        c.stop(at("23:00"))
        val third = c.stopOnLeft
        assertEquals(!first, second)
        assertEquals(!second, third)
    }

    @Test
    fun `確認の途中で1時を過ぎたら停止しない`() {
        val c = StopConfirm()
        c.stop(at("00:59"))
        c.stop(at("00:59"))
        assertEquals(StopConfirm.Result.TOO_LATE, c.stop(at("01:00")))
    }
}
