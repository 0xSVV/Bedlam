package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.core.store.Reducer

internal sealed interface Msg {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : Msg
    data object Installing : Msg
    data class Failed(val message: String) : Msg
}

internal object UpdateReducer : Reducer<UpdateStore.State, Msg> {
    override fun UpdateStore.State.reduce(msg: Msg): UpdateStore.State = when (msg) {
        is Msg.Downloading -> copy(
            phase = UpdateStore.State.Phase.Downloading(msg.downloadedBytes, msg.totalBytes),
        )

        Msg.Installing -> copy(phase = UpdateStore.State.Phase.Installing)
        is Msg.Failed -> copy(phase = UpdateStore.State.Phase.Failed(msg.message))
    }
}
