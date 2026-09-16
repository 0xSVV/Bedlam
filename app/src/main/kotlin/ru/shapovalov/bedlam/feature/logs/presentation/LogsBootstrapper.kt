package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Action {
    data class LiveUpdated(
        val entries: List<HysteriaClient.LogEntry>,
        val droppedCount: Long,
        val firstIndex: Long,
    ) : Action
}

internal class LogsBootstrapper(
    private val buffer: LogBuffer,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            buffer.snapshot.collect { snapshot ->
                dispatch(
                    Action.LiveUpdated(snapshot.entries, snapshot.droppedCount, snapshot.firstIndex)
                )
            }
        }
    }
}
