package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.usecase.GetProfilesUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveActiveProfileIdUseCase
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

internal sealed interface Action {
    data class ProfilesLoaded(val profiles: List<Profile>, val activeId: String?) : Action
    data class ConnectionStateChanged(val state: ConnectionState, val connectedSinceMillis: Long?) : Action
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
            var connectedSince: Long? = null
            client.state.collect { state ->
                connectedSince = when (state) {
                    is ConnectionState.Connected -> connectedSince ?: System.currentTimeMillis()
                    else -> null
                }
                dispatch(Action.ConnectionStateChanged(state, connectedSince))
            }
        }
    }
}
