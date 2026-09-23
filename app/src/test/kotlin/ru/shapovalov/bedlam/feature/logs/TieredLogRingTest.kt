package ru.shapovalov.bedlam.feature.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.logs.data.DroppedLines
import ru.shapovalov.bedlam.feature.logs.data.TieredLogRing
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

class TieredLogRingTest {

    private fun TieredLogRing.seqs(): List<Long> = snapshot().map { it.seq }

    @Test
    fun `debug lines evict only older debug lines`() {
        val ring = TieredLogRing(importantCapacity = 3, debugCapacity = 2)
        ring.add(logEntry(1, LogLevel.INFO))
        repeat(10) { ring.add(logEntry(10 + it, LogLevel.DEBUG)) }

        assertEquals(listOf(1L, 18L, 19L), ring.seqs())
        assertEquals(DroppedLines(debug = 8L), ring.dropped)
    }

    @Test
    fun `info and above evict only older info and above`() {
        val ring = TieredLogRing(importantCapacity = 2, debugCapacity = 5)
        ring.add(logEntry(1, LogLevel.DEBUG))
        ring.add(logEntry(2, LogLevel.INFO))
        ring.add(logEntry(3, LogLevel.WARN))
        ring.add(logEntry(4, LogLevel.ERROR))
        ring.add(logEntry(5, LogLevel.ERROR))

        assertEquals(listOf(1L, 4L, 5L), ring.seqs())
        assertEquals(DroppedLines(info = 1L, warn = 1L), ring.dropped)
    }

    @Test
    fun `the snapshot keeps arrival order across tiers`() {
        val ring = TieredLogRing(importantCapacity = 10, debugCapacity = 10)
        val levels = listOf(
            LogLevel.DEBUG, LogLevel.INFO, LogLevel.DEBUG, LogLevel.DEBUG,
            LogLevel.ERROR, LogLevel.DEBUG, LogLevel.WARN,
        )
        levels.forEachIndexed { index, level -> ring.add(logEntry(index, level)) }

        assertEquals((0L..6L).toList(), ring.seqs())
    }

    @Test
    fun `the removed count grows with every evicted or cleared line`() {
        val ring = TieredLogRing(importantCapacity = 1, debugCapacity = 1)
        ring.add(logEntry(1, LogLevel.DEBUG))
        ring.add(logEntry(2, LogLevel.INFO))
        assertEquals(0L, ring.removedCount)

        ring.add(logEntry(3, LogLevel.DEBUG))
        ring.add(logEntry(4, LogLevel.WARN))
        assertEquals(2L, ring.removedCount)

        ring.clear()
        assertEquals(4L, ring.removedCount)
        assertEquals(emptyList<Long>(), ring.seqs())
        assertEquals(DroppedLines(), ring.dropped)
    }

    @Test
    fun `dropped lines count per level and per tier`() {
        val dropped = DroppedLines(debug = 7L, info = 3L, warn = 2L, error = 1L)

        assertEquals(13L, dropped.atLeast(LogLevel.DEBUG))
        assertEquals(6L, dropped.atLeast(LogLevel.INFO))
        assertEquals(3L, dropped.atLeast(LogLevel.WARN))
        assertEquals(1L, dropped.atLeast(LogLevel.ERROR))
        assertEquals(6L, dropped.important)
    }
}
