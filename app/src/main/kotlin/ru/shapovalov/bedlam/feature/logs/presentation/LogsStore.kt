package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.core.store.Store
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
        val liveFirstIndex: Long = 0L,
        val pausedSnapshot: List<HysteriaClient.LogEntry>? = null,
        val minLevel: HysteriaClient.LogLevel = HysteriaClient.LogLevel.INFO,
        val visibleEntries: List<HysteriaClient.LogEntry> = emptyList(),
        val droppedCount: Long = 0L,
    ) {
        val isPaused: Boolean get() = pausedSnapshot != null
    }
}
