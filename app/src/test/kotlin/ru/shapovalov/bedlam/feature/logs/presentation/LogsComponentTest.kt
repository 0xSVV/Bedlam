package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.essenty.lifecycle.pause
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TestGraph
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.bedlam.testing.withComponentContext

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class LogsComponentTest {

    private fun LogsComponent.visibleSeqs(): List<Long> = state.value.visibleEntries.map { it.seq }

    @Test
    fun `the logs screen follows the buffer only while resumed`() = runTest {
        val graph = TestGraph(logScope = backgroundScope)
        withComponentContext { lifecycle, context ->
            val logs = graph.logsFactory.create(context)
            lifecycle.resume()
            graph.client.logEntries.emit(logEntry(1))
            runCurrent()
            assertEquals(listOf(1L), logs.visibleSeqs())

            lifecycle.pause()
            graph.client.logEntries.emit(logEntry(2))
            graph.client.logEntries.emit(logEntry(3))
            advanceTimeBy(1_000)
            assertEquals(listOf(1L), logs.visibleSeqs())

            lifecycle.resume()
            runCurrent()
            assertEquals(listOf(1L, 2L, 3L), logs.visibleSeqs())
        }
    }
}
