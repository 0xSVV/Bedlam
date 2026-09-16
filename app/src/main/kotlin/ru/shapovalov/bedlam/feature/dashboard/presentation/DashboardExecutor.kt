package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.DuplicateProfileException
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.model.detectProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.hysteria.ConnectionState

internal class DashboardExecutor(
    private val setActiveProfile: SetActiveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val importProfile: ImportProfileUseCase,
    private val pingProfile: suspend (Profile) -> LatencyResult,
) : CoroutineExecutor<DashboardStore.Intent, Action, DashboardStore.State, Msg, DashboardStore.Label>() {

    private val pingJobs = HashMap<String, Job>()

    override fun executeAction(action: Action) {
        when (action) {
            is Action.ProfilesLoaded -> {
                cancelPingsOfRemovedProfiles(action.profiles)
                dispatch(
                    Msg.ProfilesLoaded(
                        action.profiles,
                        action.activeId
                    )
                )
            }

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
            is DashboardStore.Intent.OpenImport -> openImport(intent.prefill)
            DashboardStore.Intent.CloseImport -> dispatch(Msg.ImportSheetClosed)
            is DashboardStore.Intent.ImportProfile ->
                handleImport(intent.format, intent.text, intent.name)

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

    private fun openImport(prefill: String) {
        val text = prefill.trim()
        dispatch(
            Msg.ImportSheetOpened(
                DashboardStore.ImportSheetSeed(text, detectProfileImportFormat(text))
            )
        )
    }

    private fun handleImport(format: ProfileImportFormat, text: String, name: String) {
        if (state().isImporting) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        dispatch(Msg.ImportStarted)
        scope.launch {
            importProfile(trimmed, format, name.trim().ifEmpty { null })
                .onSuccess { profile ->
                    dispatch(Msg.ImportSucceeded)
                    if (state().activeProfileId == null) setActiveProfile(profile.id)
                }
                .onFailure { e ->
                    when (e) {
                        is DuplicateProfileException ->
                            dispatch(Msg.ImportRejectedAsDuplicate(e.existingName))

                        else -> dispatch(Msg.ImportFailed(e.message.orEmpty()))
                    }
                }
        }
    }

    private fun ping(id: String) {
        state().profiles.firstOrNull { it.id == id }?.let(::measure)
    }

    private fun pingAll() {
        state().profiles.forEach(::measure)
    }

    private fun measure(profile: Profile) {
        pingJobs[profile.id]?.cancel()
        dispatch(Msg.LatencyUpdated(profile.id, LatencyResult.Measuring))
        pingJobs[profile.id] = scope.launch {
            dispatch(Msg.LatencyUpdated(profile.id, pingProfile(profile)))
        }
    }

    private fun cancelPingsOfRemovedProfiles(profiles: List<Profile>) {
        val ids = profiles.mapTo(HashSet()) { it.id }
        (pingJobs.keys - ids).forEach { id -> pingJobs.remove(id)?.cancel() }
    }

    private fun pingActiveProfile() {
        val id = state().activeProfileId ?: return
        ping(id)
    }
}
