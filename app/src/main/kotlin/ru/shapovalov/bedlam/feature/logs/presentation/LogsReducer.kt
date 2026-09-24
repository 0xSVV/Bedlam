package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.feature.logs.data.DroppedLines
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Msg {
    data class LiveUpdated(
        val entries: List<HysteriaClient.LogEntry>,
        val dropped: DroppedLines,
        val removedCount: Long,
    ) : Msg
    data class MinLevelChanged(val level: HysteriaClient.LogLevel) : Msg
    data class Paused(val snapshot: List<HysteriaClient.LogEntry>) : Msg
    data object Resumed : Msg
}

internal object LogsReducer : Reducer<LogsStore.State, Msg> {
    override fun LogsStore.State.reduce(msg: Msg): LogsStore.State = when (msg) {
        is Msg.LiveUpdated -> copy(
            liveEntries = msg.entries,
            liveRemovedCount = msg.removedCount,
            dropped = msg.dropped,
            visibleEntries = if (isPaused) visibleEntries else visibleAfter(msg),
        )
        is Msg.MinLevelChanged -> copy(minLevel = msg.level).withVisibleEntries()
        is Msg.Paused -> copy(pausedSnapshot = msg.snapshot).withVisibleEntries()
        Msg.Resumed -> copy(pausedSnapshot = null).withVisibleEntries()
    }
}

private fun LogsStore.State.withVisibleEntries(): LogsStore.State =
    copy(visibleEntries = (pausedSnapshot ?: liveEntries).atLeast(minLevel))

private fun LogsStore.State.visibleAfter(
    update: Msg.LiveUpdated,
): List<HysteriaClient.LogEntry> {
    val entries = update.entries
    val onlyAppended = update.removedCount == liveRemovedCount && entries.size >= liveEntries.size
    return when {
        minLevel == HysteriaClient.LogLevel.DEBUG -> entries
        !onlyAppended -> entries.atLeast(minLevel)
        entries.size == liveEntries.size -> visibleEntries
        else -> visibleEntries + entries.subList(liveEntries.size, entries.size).atLeast(minLevel)
    }
}

private fun List<HysteriaClient.LogEntry>.atLeast(
    level: HysteriaClient.LogLevel,
): List<HysteriaClient.LogEntry> =
    if (level == HysteriaClient.LogLevel.DEBUG) this else filter { it.level >= level }
