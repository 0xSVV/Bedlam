package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry

@OptIn(ExperimentalCoroutinesApi::class)
class LogsExecutorTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension { StandardTestDispatcher() }

    private val client = FakeHysteriaClient()

    private fun TestScope.store(buffer: LogBuffer = LogBuffer(client, backgroundScope)): LogsStore =
        LogsStoreFactory(DefaultStoreFactory(), buffer).create()

    private fun List<LogEntry>.seqs(): List<Long> = map { it.seq }

    private suspend fun emitLines(range: IntRange) {
        range.forEach { client.logEntries.emit(logEntry(it)) }
    }

    @Test
    fun `live lines reach the list only in the foreground and catch up on return`() = runTest {
        store().disposeAfter { store ->
            store.accept(LogsStore.Intent.SetForeground(true))
            emitLines(1..1)
            runCurrent()
            assertEquals(listOf(1L), store.state.visibleEntries.seqs())

            store.accept(LogsStore.Intent.SetForeground(false))
            emitLines(2..3)
            advanceTimeBy(1_000)
            assertEquals(listOf(1L), store.state.visibleEntries.seqs())

            store.accept(LogsStore.Intent.SetForeground(true))
            runCurrent()
            assertEquals(listOf(1L, 2L, 3L), store.state.visibleEntries.seqs())
        }
    }

    @Test
    fun `toggling pause snapshots the live lines and toggling again resumes`() = runTest {
        store().disposeAfter { store ->
            store.accept(LogsStore.Intent.SetForeground(true))
            emitLines(0..2)
            runCurrent()

            store.accept(LogsStore.Intent.TogglePaused)
            assertTrue(store.state.isPaused)
            assertEquals(store.state.liveEntries, store.state.pausedSnapshot)

            emitLines(3..3)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf(0L, 1L, 2L), store.state.visibleEntries.seqs())
            assertEquals(listOf(0L, 1L, 2L, 3L), store.state.liveEntries.seqs())

            store.accept(LogsStore.Intent.TogglePaused)
            assertFalse(store.state.isPaused)
            assertEquals(listOf(0L, 1L, 2L, 3L), store.state.visibleEntries.seqs())
        }
    }

    @Test
    fun `clear empties the buffer and the list and resumes`() = runTest {
        val buffer = LogBuffer(client, backgroundScope)
        store(buffer).disposeAfter { store ->
            store.accept(LogsStore.Intent.SetForeground(true))
            emitLines(0..2)
            runCurrent()
            store.accept(LogsStore.Intent.TogglePaused)

            store.accept(LogsStore.Intent.Clear)
            assertEquals(emptyList<LogEntry>(), buffer.snapshot.value.entries)
            assertEquals(emptyList<LogEntry>(), store.state.visibleEntries)
            assertFalse(store.state.isPaused)

            emitLines(3..3)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf(3L), store.state.visibleEntries.seqs())
        }
    }
}
