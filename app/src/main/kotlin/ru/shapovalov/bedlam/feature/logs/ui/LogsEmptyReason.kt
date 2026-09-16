package ru.shapovalov.bedlam.feature.logs.ui

import ru.shapovalov.bedlam.feature.logs.presentation.LogsStore

internal enum class LogsEmptyReason { Filtered, Paused, Idle }

internal fun LogsStore.State.emptyReason(): LogsEmptyReason? = when {
    visibleEntries.isNotEmpty() -> null
    (pausedSnapshot ?: liveEntries).isNotEmpty() -> LogsEmptyReason.Filtered
    isPaused -> LogsEmptyReason.Paused
    else -> LogsEmptyReason.Idle
}
