package me.riverfrozer.penguinsmagic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连锁封禁的窗口算术。
 *
 * 「延长 30 秒」必须是在剩余窗口上加 30 秒：写成「从当前时刻起 30 秒」时，
 * 窗口还剩 80 秒的情况下反而会把封禁缩短，用户看到的剩余时间会往回跳。
 * 上限则从这次封禁的起点算起：骚扰电话一直打进来时，窗口不能被无限推后。
 */
class ChainBanPolicyTest {

    private val start = 1_000_000L
    private val fiveMinutes = ChainBanPolicy.maxMs(5)

    @Test
    fun `a real block opens a ninety second window`() {
        assertEquals(start + 90_000L, ChainBanPolicy.armedUntil(0L, start, start, fiveMinutes))
        val armed = ChainBanPolicy.armedUntil(0L, start, start, fiveMinutes)
        assertTrue(ChainBanPolicy.isActive(armed, start + 89_999L))
        assertFalse(ChainBanPolicy.isActive(armed, start + 90_000L))
    }

    @Test
    fun `arming again never shortens a longer window`() {
        val extended = start + 200_000L
        assertEquals(extended, ChainBanPolicy.armedUntil(extended, start, start, fiveMinutes))
    }

    @Test
    fun `each chained block adds thirty seconds to what is left`() {
        val armed = ChainBanPolicy.armedUntil(0L, start, start, fiveMinutes)
        // 20 秒后拦下 B：剩 70 秒，延长后剩 100 秒
        val afterB = ChainBanPolicy.extendedUntil(armed, start, start + 20_000L, fiveMinutes)
        assertEquals(start + 120_000L, afterB)
        // 又 10 秒后拦下 C：再加 30 秒
        val afterC = ChainBanPolicy.extendedUntil(afterB, start, start + 30_000L, fiveMinutes)
        assertEquals(start + 150_000L, afterC)
        assertTrue(ChainBanPolicy.isActive(afterC, start + 149_999L))
    }

    @Test
    fun `extending an expired window starts from now`() {
        assertEquals(start + 30_000L, ChainBanPolicy.extendedUntil(start - 5_000L, start, start, fiveMinutes))
    }

    @Test
    fun `endless incoming calls cannot push the window past the cap`() {
        val cap = ChainBanPolicy.maxMs(3)
        var until = ChainBanPolicy.armedUntil(0L, start, start, cap)
        // 每 20 秒进来一个陌生号码，一直拦下去
        var now = start
        repeat(30) {
            now += 20_000L
            until = ChainBanPolicy.extendedUntil(until, start, now, cap)
            assertTrue("窗口越过了上限", until <= start + cap)
        }
        assertEquals(start + cap, until)
        assertFalse(ChainBanPolicy.isActive(until, start + cap))
    }

    @Test
    fun `a cap shorter than the initial window wins`() {
        val cap = ChainBanPolicy.maxMs(1)
        assertEquals(start + cap, ChainBanPolicy.armedUntil(0L, start, start, cap))
    }

    @Test
    fun `a new chain after the cap expires starts its own budget`() {
        val cap = ChainBanPolicy.maxMs(3)
        val expired = start + cap
        val restarted = expired + 10_000L
        assertEquals(
            restarted + 90_000L,
            ChainBanPolicy.armedUntil(expired, restarted, restarted, cap),
        )
    }

    @Test
    fun `minutes are clamped to the range the slider offers`() {
        assertEquals(ChainBanPolicy.maxMs(1), ChainBanPolicy.maxMs(0))
        assertEquals(ChainBanPolicy.maxMs(30), ChainBanPolicy.maxMs(999))
        assertEquals(300_000L, ChainBanPolicy.maxMs(5))
    }

    @Test
    fun `temporary pass lasts ten minutes`() {
        assertEquals(600_000L, ChainBanPolicy.TEMP_PASS_MS)
    }
}