package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileIdUseCase
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Action {
    data class ProfilesLoaded(val profiles: List<Profile>, val activeId: String?) : Action
    data class ConnectionStateChanged(val state: ConnectionState, val connectedSinceMillis: Long?) : Action
    data object TunnelConnected : Action
}

internal class DashboardBootstrapper(
    private val getProfiles: GetProfilesUseCase,
    private val observeActiveId: ObserveActiveProfileIdUseCase,
    private val client: HysteriaClient,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            combine(getProfiles(), observeActiveId()) { profiles, activeId ->
                Action.ProfilesLoaded(profiles, activeId)
            }.collect(::dispatch)
        }
        scope.launch {
            client.state.collect { state ->
                dispatch(Action.ConnectionStateChanged(state, (state as? ConnectionState.Connected)?.connectedSinceMillis))
            }
        }
        scope.launch {
            client.state
                .map { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .collect { isConnected -> if (isConnected) dispatch(Action.TunnelConnected) }
        }
    }
}
