package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Msg {
    data class LiveUpdated(val entries: List<HysteriaClient.LogEntry>) : Msg
    data class MinLevelChanged(val level: HysteriaClient.LogLevel) : Msg
    data class Paused(val snapshot: List<HysteriaClient.LogEntry>) : Msg
    data object Resumed : Msg
}

internal object LogsReducer : Reducer<LogsStore.State, Msg> {
    override fun LogsStore.State.reduce(msg: Msg): LogsStore.State = when (msg) {
        is Msg.LiveUpdated -> copy(liveEntries = msg.entries)
        is Msg.MinLevelChanged -> copy(minLevel = msg.level)
        is Msg.Paused -> copy(pausedSnapshot = msg.snapshot)
        Msg.Resumed -> copy(pausedSnapshot = null)
    }
}
