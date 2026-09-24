package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.feature.logs.data.DroppedLines
import ru.shapovalov.hysteria.api.HysteriaClient

interface LogsStore : Store<LogsStore.Intent, LogsStore.State, Nothing> {

    sealed interface Intent {
        data class SetForeground(val foreground: Boolean) : Intent
        data class ChangeMinLevel(val level: HysteriaClient.LogLevel) : Intent
        data object TogglePaused : Intent
        data object Clear : Intent
    }

    data class State(
        val liveEntries: List<HysteriaClient.LogEntry> = emptyList(),
        val liveRemovedCount: Long = 0L,
        val pausedSnapshot: List<HysteriaClient.LogEntry>? = null,
        val minLevel: HysteriaClient.LogLevel = HysteriaClient.LogLevel.INFO,
        val visibleEntries: List<HysteriaClient.LogEntry> = emptyList(),
        val dropped: DroppedLines = DroppedLines(),
    ) {
        val isPaused: Boolean get() = pausedSnapshot != null
        val droppedCount: Long get() = dropped.atLeast(minLevel)
    }
}
