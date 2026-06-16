package ru.shapovalov.bedlam.core.vpn.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateHistoryTest {

    @Test
    fun `records samples in order`() {
        val history = RateHistory(capacity = 4)
        history.record(10, 20)
        history.record(30, 40)

        val snapshot = history.snapshot()
        assertEquals(listOf(10L, 30L), snapshot.tx)
        assertEquals(listOf(20L, 40L), snapshot.rx)
    }

    @Test
    fun `evicts oldest beyond capacity`() {
        val history = RateHistory(capacity = 2)
        history.record(1, 1)
        history.record(2, 2)
        history.record(3, 3)

        val snapshot = history.snapshot()
        assertEquals(listOf(2L, 3L), snapshot.tx)
        assertEquals(listOf(2L, 3L), snapshot.rx)
    }

    @Test
    fun `clamps negative rates to zero`() {
        val history = RateHistory(capacity = 2)
        history.record(-5, -7)

        val snapshot = history.snapshot()
        assertEquals(listOf(0L), snapshot.tx)
        assertEquals(listOf(0L), snapshot.rx)
    }

    @Test
    fun `clear empties history`() {
        val history = RateHistory(capacity = 2)
        history.record(1, 1)
        history.clear()

        assertEquals(0, history.snapshot().size)
    }
}
