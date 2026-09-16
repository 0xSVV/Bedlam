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

    private fun LogRing.messages(): List<String> = snapshot().map { it.message }

    @Test
    fun `drops the oldest entries once capacity is reached`() {
        val ring = LogRing(capacity = 1000)
        repeat(1100) { ring.add(entry(it)) }

        val snapshot = ring.snapshot()
        assertEquals(1000, snapshot.size)
        assertEquals("line 100", snapshot.first().message)
        assertEquals("line 1099", snapshot.last().message)
        assertEquals(100L, ring.droppedCount)
    }

    @Test
    fun `clear empties the ring`() {
        val ring = LogRing(capacity = 4)
        repeat(3) { ring.add(entry(it)) }

        ring.clear()
        assertEquals(emptyList<String>(), ring.messages())
        assertEquals(0L, ring.droppedCount)

        ring.add(entry(9))
        assertEquals(listOf("line 9"), ring.messages())
    }

    @Test
    fun `a snapshot does not change after later additions`() {
        val ring = LogRing(capacity = 3)
        repeat(3) { ring.add(entry(it)) }

        val snapshot = ring.snapshot()
        ring.add(entry(3))

        assertEquals(listOf("line 0", "line 1", "line 2"), snapshot.map { it.message })
        assertEquals(listOf("line 1", "line 2", "line 3"), ring.messages())
    }
}
