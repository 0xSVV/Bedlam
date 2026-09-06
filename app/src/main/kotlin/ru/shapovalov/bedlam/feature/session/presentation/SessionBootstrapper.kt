package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Action {
    data class TunnelStateChanged(val state: ConnectionState) : Action
}

internal class SessionBootstrapper(
    private val client: HysteriaClient,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            client.state.collect { dispatch(Action.TunnelStateChanged(it)) }
        }
    }
}
