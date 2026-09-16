package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer

internal class LogsExecutor(
    private val buffer: LogBuffer,
) : CoroutineExecutor<LogsStore.Intent, Nothing, LogsStore.State, Msg, Nothing>() {

    private var liveJob: Job? = null

    override fun executeIntent(intent: LogsStore.Intent) {
        when (intent) {
            is LogsStore.Intent.SetForeground -> setForeground(intent.foreground)
            is LogsStore.Intent.ChangeMinLevel -> dispatch(Msg.MinLevelChanged(intent.level))
            LogsStore.Intent.TogglePaused -> {
                val s = state()
                if (s.isPaused) dispatch(Msg.Resumed)
                else dispatch(Msg.Paused(s.liveEntries))
            }

            LogsStore.Intent.Clear -> {
                buffer.clear()
                dispatch(buffer.snapshot.value.toLiveUpdated())
                dispatch(Msg.Resumed)
            }
        }
    }

    private fun setForeground(foreground: Boolean) {
        liveJob?.cancel()
        liveJob = if (foreground) {
            scope.launch {
                buffer.snapshot.collect { dispatch(it.toLiveUpdated()) }
            }
        } else {
            null
        }
    }
}

private fun LogBuffer.Snapshot.toLiveUpdated() = Msg.LiveUpdated(entries, droppedCount, firstIndex)
