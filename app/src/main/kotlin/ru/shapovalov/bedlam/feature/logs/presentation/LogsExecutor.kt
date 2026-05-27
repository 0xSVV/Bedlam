package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer

internal class LogsExecutor(
    private val buffer: LogBuffer,
) : CoroutineExecutor<LogsStore.Intent, Action, LogsStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.LiveUpdated -> dispatch(Msg.LiveUpdated(action.entries))
        }
    }

    override fun executeIntent(intent: LogsStore.Intent) {
        when (intent) {
            is LogsStore.Intent.ChangeMinLevel -> dispatch(Msg.MinLevelChanged(intent.level))
            LogsStore.Intent.TogglePaused -> {
                val s = state()
                if (s.isPaused) dispatch(Msg.Resumed)
                else dispatch(Msg.Paused(s.liveEntries))
            }

            LogsStore.Intent.Clear -> {
                buffer.clear()
                dispatch(Msg.Resumed)
            }
        }
    }
}
