package ru.shapovalov.bedlam.core.vpn.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateHistoryTest {

    @Test
    fun `records samples in order`() {
        val history = RateHistory(capacity = 4)
        history.record(10)
        history.record(30)

        assertEquals(listOf(10L, 30L), history.snapshot())
    }

    @Test
    fun `evicts oldest beyond capacity`() {
        val history = RateHistory(capacity = 2)
        history.record(1)
        history.record(2)
        history.record(3)

        assertEquals(listOf(2L, 3L), history.snapshot())
    }

    @Test
    fun `clamps negative rates to zero`() {
        val history = RateHistory(capacity = 2)
        history.record(-5)

        assertEquals(listOf(0L), history.snapshot())
    }

    @Test
    fun `clear empties history`() {
        val history = RateHistory(capacity = 2)
        history.record(1)
        history.clear()

        assertEquals(emptyList<Long>(), history.snapshot())
    }
}
