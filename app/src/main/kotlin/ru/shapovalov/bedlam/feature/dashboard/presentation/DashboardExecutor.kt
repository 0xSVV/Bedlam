package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.latency.PingProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileFromUriUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.hysteria.ConnectionState

internal class DashboardExecutor(
    private val setActiveProfile: SetActiveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val importFromUri: ImportProfileFromUriUseCase,
    private val pingProfile: PingProfileUseCase,
) : CoroutineExecutor<DashboardStore.Intent, Action, DashboardStore.State, Msg, DashboardStore.Label>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.ProfilesLoaded -> dispatch(
                Msg.ProfilesLoaded(
                    action.profiles,
                    action.activeId
                )
            )

            is Action.ConnectionStateChanged -> dispatch(
                Msg.ConnectionChanged(
                    action.state,
                    action.connectedSinceMillis
                )
            )

            Action.TunnelConnected -> pingActiveProfile()
        }
    }

    override fun executeIntent(intent: DashboardStore.Intent) {
        when (intent) {
            DashboardStore.Intent.ToggleConnection -> toggleConnection()
            is DashboardStore.Intent.SelectProfile -> scope.launch { setActiveProfile(intent.id) }
            is DashboardStore.Intent.DeleteProfile -> scope.launch { deleteProfile(intent.id) }
            is DashboardStore.Intent.ImportProfileFromUri -> handleImport(intent.uri)
            DashboardStore.Intent.DismissError -> dispatch(Msg.ErrorDismissed)
            is DashboardStore.Intent.PingProfile -> ping(intent.id)
            DashboardStore.Intent.PingAllProfiles -> pingAll()
        }
    }

    private fun toggleConnection() {
        val s = state()
        when (s.connectionState) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.Reconnecting -> publish(DashboardStore.Label.RequestStopVpn)

            else -> {
                val active = s.activeProfile
                if (active == null) {
                    dispatch(Msg.ErrorRaised(DashboardStore.ErrorReason.NoActiveProfile))
                } else {
                    publish(DashboardStore.Label.RequestStartVpn(active))
                }
            }
        }
    }

    private fun handleImport(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) {
            dispatch(Msg.ErrorRaised(DashboardStore.ErrorReason.ClipboardEmpty))
            return
        }
        dispatch(Msg.ImportStarted)
        scope.launch {
            importFromUri(trimmed)
                .onSuccess { profile ->
                    dispatch(Msg.ImportFinished)
                    if (state().activeProfileId == null) setActiveProfile(profile.id)
                }
                .onFailure { e ->
                    dispatch(Msg.ImportFinished)
                    dispatch(Msg.ErrorRaised(DashboardStore.ErrorReason.ImportFailed(e.message)))
                }
        }
    }

    private fun ping(id: String) {
        val profile = state().profiles.firstOrNull { it.id == id } ?: return
        dispatch(Msg.LatencyUpdated(id, LatencyResult.Measuring))
        scope.launch {
            dispatch(Msg.LatencyUpdated(id, pingProfile(profile)))
        }
    }

    private fun pingAll() {
        state().profiles.forEach { profile ->
            dispatch(Msg.LatencyUpdated(profile.id, LatencyResult.Measuring))
            scope.launch {
                dispatch(Msg.LatencyUpdated(profile.id, pingProfile(profile)))
            }
        }
    }

    private fun pingActiveProfile() {
        val id = state().activeProfileId ?: return
        ping(id)
    }
}
