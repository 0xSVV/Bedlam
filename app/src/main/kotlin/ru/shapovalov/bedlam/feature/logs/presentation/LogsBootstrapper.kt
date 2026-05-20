package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Action {
    data class LiveUpdated(val entries: List<HysteriaClient.LogEntry>) : Action
}

internal class LogsBootstrapper(
    private val buffer: LogBuffer,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            buffer.entries.collect { entries -> dispatch(Action.LiveUpdated(entries)) }
        }
    }
}
