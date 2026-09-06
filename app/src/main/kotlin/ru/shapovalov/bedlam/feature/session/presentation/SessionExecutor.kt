package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository
import ru.shapovalov.hysteria.ConnectionState

internal class SessionExecutor(
    private val repository: SessionInfoRepository,
) : CoroutineExecutor<SessionStore.Intent, Action, SessionStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.TunnelStateChanged -> onTunnelState(action.state)
        }
    }

    override fun executeIntent(intent: SessionStore.Intent) {
        when (intent) {
            SessionStore.Intent.Refresh -> load()
        }
    }

    private fun onTunnelState(tunnel: ConnectionState) {
        when (tunnel) {
            is ConnectionState.Connected -> {
                dispatch(Msg.TunnelUp)
                if (state().info == null || state().isStale) load()
            }

            is ConnectionState.Connecting, is ConnectionState.Reconnecting ->
                dispatch(Msg.TunnelWobbling)

            is ConnectionState.Disconnected, is ConnectionState.Error ->
                dispatch(Msg.TunnelDown)
        }
    }

    // The lookup leaves through whatever route is up. Off-tunnel it would
    // report the device's own address, which on this screen reads as the
    // tunnel's — the one error a privacy tool must never make.
    private fun load() {
        if (state().isLoading || !state().tunnelUp) return
        dispatch(Msg.LoadingStarted)
        scope.launch {
            repository.fetch()
                .onSuccess { dispatch(Msg.LoadingSucceeded(it)) }
                .onFailure { dispatch(Msg.LoadingFailed(it.message)) }
        }
    }
}
