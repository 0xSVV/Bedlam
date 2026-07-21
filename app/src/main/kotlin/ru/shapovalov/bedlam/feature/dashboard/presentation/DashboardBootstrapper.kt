package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileIdUseCase
import ru.shapovalov.bedlam.core.vpn.ReconcileConnectionStateUseCase
import ru.shapovalov.bedlam.core.vpn.VpnRuntimeStateRepository
import ru.shapovalov.bedlam.core.vpn.effectiveWith
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Action {
    data class ProfilesLoaded(val profiles: List<Profile>, val activeId: String?) : Action
    data class ConnectionStateChanged(val state: ConnectionState, val connectedSinceMillis: Long?) :
        Action

    data object TunnelConnected : Action
}

internal class DashboardBootstrapper(
    private val getProfiles: GetProfilesUseCase,
    private val observeActiveId: ObserveActiveProfileIdUseCase,
    private val client: HysteriaClient,
    private val runtimeStateRepository: VpnRuntimeStateRepository,
    private val reconcileConnectionState: ReconcileConnectionStateUseCase,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            combine(getProfiles(), observeActiveId()) { profiles, activeId ->
                Action.ProfilesLoaded(profiles, activeId)
            }.collect(::dispatch)
        }
        scope.launch {
            runCatching { reconcileConnectionState() }
            var wasConnected = false
            combine(client.state, runtimeStateRepository.state) { state, runtimeState ->
                state.effectiveWith(runtimeState)
            }.collect { state ->
                val isConnected = state is ConnectionState.Connected
                dispatch(
                    Action.ConnectionStateChanged(
                        state,
                        (state as? ConnectionState.Connected)?.connectedSinceMillis
                    )
                )
                if (isConnected && !wasConnected) dispatch(Action.TunnelConnected)
                wasConnected = isConnected
            }
        }
    }
}
