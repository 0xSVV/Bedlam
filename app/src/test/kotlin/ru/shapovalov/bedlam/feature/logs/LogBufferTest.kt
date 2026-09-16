package ru.shapovalov.bedlam.feature.logs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.logEntry

@OptIn(ExperimentalCoroutinesApi::class)
class LogBufferTest {

    private fun TestScope.record(buffer: LogBuffer): List<LogBuffer.Snapshot> {
        val published = mutableListOf<LogBuffer.Snapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            buffer.snapshot.collect { published += it }
        }
        return published
    }

    private fun List<LogBuffer.Snapshot>.lastSeqs(): List<Long> = last().entries.map { it.seq }

    @Test
    fun `a line after a quiet period is published at once and later lines wait`() = runTest {
        val client = FakeHysteriaClient()
        val published = record(LogBuffer(client, backgroundScope))
        runCurrent()

        client.logEntries.emit(logEntry(1))
        runCurrent()
        assertEquals(listOf(1L), published.lastSeqs())

        client.logEntries.emit(logEntry(2))
        client.logEntries.emit(logEntry(3))
        runCurrent()
        assertEquals(listOf(1L), published.lastSeqs())

        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), published.lastSeqs())
    }

    @Test
    fun `a steady stream is published at most once per interval`() = runTest {
        val client = FakeHysteriaClient()
        val published = record(LogBuffer(client, backgroundScope))
        runCurrent()

        repeat(100) { index ->
            client.logEntries.emit(logEntry(index))
            advanceTimeBy(10)
        }
        advanceTimeBy(100)
        runCurrent()

        val emissions = published.size - 1
        assertTrue(emissions <= 11, "published $emissions snapshots for 100 lines in a second")
        assertEquals((0L until 100L).toList(), published.lastSeqs())
    }

    @Test
    fun `clear publishes an empty snapshot at once`() = runTest {
        val client = FakeHysteriaClient()
        val buffer = LogBuffer(client, backgroundScope)
        val published = record(buffer)
        runCurrent()
        repeat(3) { client.logEntries.emit(logEntry(it)) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(0L, 1L, 2L), published.lastSeqs())

        buffer.clear()

        assertEquals(LogBuffer.Snapshot(), buffer.snapshot.value)
        assertEquals(LogBuffer.Snapshot(), published.last())
    }
}
