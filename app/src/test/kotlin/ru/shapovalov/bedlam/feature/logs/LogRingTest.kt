package ru.shapovalov.bedlam.feature.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.logs.data.LogRing
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

class LogRingTest {

    private fun entry(index: Int) = LogEntry(
        level = LogLevel.INFO,
        source = "test",
        message = "line $index",
        timestampMillis = index.toLong(),
    )

    @Test
    fun `drops the oldest entries once capacity is reached`() {
        val ring = LogRing(capacity = 1000)
        var snapshot = emptyList<LogEntry>()
        repeat(1100) { snapshot = ring.add(entry(it)) }

        assertEquals(1000, snapshot.size)
        assertEquals("line 100", snapshot.first().message)
        assertEquals("line 1099", snapshot.last().message)
        assertEquals(100L, ring.droppedCount)
    }

    @Test
    fun `clear empties the ring`() {
        val ring = LogRing(capacity = 4)
        repeat(3) { ring.add(entry(it)) }
        assertEquals(emptyList<LogEntry>(), ring.clear())
        assertEquals(0L, ring.droppedCount)
        assertEquals(listOf("line 9"), ring.add(entry(9)).map { it.message })
    }
}
