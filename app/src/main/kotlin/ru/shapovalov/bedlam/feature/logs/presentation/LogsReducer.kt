package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Msg {
    data class LiveUpdated(
        val entries: List<HysteriaClient.LogEntry>,
        val droppedCount: Long,
    ) : Msg
    data class MinLevelChanged(val level: HysteriaClient.LogLevel) : Msg
    data class Paused(val snapshot: List<HysteriaClient.LogEntry>) : Msg
    data object Resumed : Msg
}

internal object LogsReducer : Reducer<LogsStore.State, Msg> {
    override fun LogsStore.State.reduce(msg: Msg): LogsStore.State = when (msg) {
        is Msg.LiveUpdated ->
            copy(liveEntries = msg.entries, droppedCount = msg.droppedCount).withVisibleEntries()
        is Msg.MinLevelChanged -> copy(minLevel = msg.level).withVisibleEntries()
        is Msg.Paused -> copy(pausedSnapshot = msg.snapshot).withVisibleEntries()
        Msg.Resumed -> copy(pausedSnapshot = null).withVisibleEntries()
    }
}

private fun LogsStore.State.withVisibleEntries(): LogsStore.State = copy(
    visibleEntries = (pausedSnapshot ?: liveEntries)
        .filter { it.level.ordinal >= minLevel.ordinal },
)
