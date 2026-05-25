package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository

internal class SessionExecutor(
    private val repository: SessionInfoRepository,
) : CoroutineExecutor<SessionStore.Intent, Action, SessionStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            Action.Load -> load()
        }
    }

    override fun executeIntent(intent: SessionStore.Intent) {
        when (intent) {
            SessionStore.Intent.Refresh -> load()
        }
    }

    private fun load() {
        if (state().isLoading) return
        dispatch(Msg.LoadingStarted)
        scope.launch {
            repository.fetch()
                .onSuccess { dispatch(Msg.LoadingSucceeded(it)) }
                .onFailure { dispatch(Msg.LoadingFailed(it.message)) }
        }
    }
}
