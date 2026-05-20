package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo

internal sealed interface Msg {
    data object LoadingStarted : Msg
    data class LoadingSucceeded(val info: SessionInfo) : Msg
    data class LoadingFailed(val message: String?) : Msg
}

internal object SessionReducer : Reducer<SessionStore.State, Msg> {
    override fun SessionStore.State.reduce(msg: Msg): SessionStore.State = when (msg) {
        Msg.LoadingStarted -> copy(isLoading = true, errorMessage = null)
        is Msg.LoadingSucceeded -> copy(info = msg.info, isLoading = false, errorMessage = null)
        is Msg.LoadingFailed -> copy(isLoading = false, errorMessage = msg.message)
    }
}
