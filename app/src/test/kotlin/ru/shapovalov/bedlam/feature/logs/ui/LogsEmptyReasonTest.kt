package ru.shapovalov.bedlam.feature.logs.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.logs.data.DroppedLines
import ru.shapovalov.bedlam.feature.logs.presentation.LogsReducer
import ru.shapovalov.bedlam.feature.logs.presentation.LogsStore
import ru.shapovalov.bedlam.feature.logs.presentation.Msg
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.bedlam.testing.reduceAll
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

class LogsEmptyReasonTest {

    @Test
    fun `a filter that hides every line names the filter`() {
        val state = LogsReducer.reduceAll(
            LogsStore.State(),
            Msg.LiveUpdated(listOf(logEntry(1, LogLevel.DEBUG), logEntry(2, LogLevel.INFO)), DroppedLines(), 0L),
            Msg.MinLevelChanged(LogLevel.ERROR),
        )

        assertEquals(LogsEmptyReason.Filtered, state.emptyReason())
    }

    @Test
    fun `an empty buffer waits for output at any level`() {
        val waiting = LogsReducer.reduceAll(LogsStore.State(), Msg.MinLevelChanged(LogLevel.ERROR))
        assertEquals(LogsEmptyReason.Idle, waiting.emptyReason())

        val shown = LogsReducer.reduceAll(
            waiting,
            Msg.LiveUpdated(listOf(logEntry(1, LogLevel.ERROR)), DroppedLines(), 0L),
        )
        assertNull(shown.emptyReason())
    }

    @Test
    fun `a paused snapshot hidden by the filter names the filter`() {
        val lines = listOf(logEntry(1, LogLevel.INFO))
        val paused = LogsReducer.reduceAll(
            LogsStore.State(),
            Msg.LiveUpdated(lines, DroppedLines(), 0L),
            Msg.Paused(lines),
            Msg.MinLevelChanged(LogLevel.WARN),
        )
        assertEquals(LogsEmptyReason.Filtered, paused.emptyReason())

        val cleared = LogsReducer.reduceAll(paused, Msg.Resumed, Msg.LiveUpdated(emptyList(), DroppedLines(), 1L))
        assertEquals(LogsEmptyReason.Idle, cleared.emptyReason())
    }

    @Test
    fun `an empty paused snapshot says the list is paused`() {
        val paused = LogsReducer.reduceAll(LogsStore.State(), Msg.Paused(emptyList()))

        assertEquals(LogsEmptyReason.Paused, paused.emptyReason())
    }
}
