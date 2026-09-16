package ru.shapovalov.bedlam.core.vpn.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateHistoryTest {

    @Test
    fun `merges samples in the same slot by max`() {
        val history = RateHistory(slots = 3, slotMillis = 2_000)
        history.record(10, atMillis = 0)
        history.record(30, atMillis = 1_500)
        history.record(20, atMillis = 1_999)

        assertEquals(listOf(0L, 0L, 30L), history.snapshot(nowMillis = 1_999))
    }

    @Test
    fun `starts a new slot after slot millis`() {
        val history = RateHistory(slots = 3, slotMillis = 2_000)
        history.record(10, atMillis = 0)
        history.record(20, atMillis = 2_000)

        assertEquals(listOf(0L, 10L, 20L), history.snapshot(nowMillis = 2_000))
    }

    @Test
    fun `slots without samples read as zero`() {
        val history = RateHistory(slots = 3, slotMillis = 2_000)
        history.record(10, atMillis = 0)

        assertEquals(listOf(10L, 0L, 0L), history.snapshot(nowMillis = 4_000))
    }

    @Test
    fun `drops slots older than the window`() {
        val history = RateHistory(slots = 3, slotMillis = 2_000)
        history.record(10, atMillis = 0)
        history.record(20, atMillis = 2_000)
        history.record(30, atMillis = 4_000)
        history.record(40, atMillis = 6_000)

        assertEquals(listOf(20L, 30L, 40L), history.snapshot(nowMillis = 6_000))
    }

    @Test
    fun `a gap longer than the window leaves only the newest sample`() {
        val history = RateHistory(slots = 3, slotMillis = 2_000)
        history.record(10, atMillis = 0)
        history.record(20, atMillis = 100_000)

        assertEquals(listOf(0L, 0L, 20L), history.snapshot(nowMillis = 100_000))
    }

    @Test
    fun `clamps negative rates to zero`() {
        val history = RateHistory(slots = 1, slotMillis = 2_000)
        history.record(-5, atMillis = 0)

        assertEquals(listOf(0L), history.snapshot(nowMillis = 0))
    }

    @Test
    fun `clear empties history`() {
        val history = RateHistory(slots = 2, slotMillis = 2_000)
        history.record(1, atMillis = 0)
        history.clear()

        assertEquals(listOf(0L, 0L), history.snapshot(nowMillis = 0))
    }
}
