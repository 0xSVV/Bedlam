package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportBatch
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFailure
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.model.detectProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.isActiveTunnel

internal class DashboardExecutor(
    private val setActiveProfile: SetActiveProfileUseCase,
    private val importProfile: ImportProfileUseCase,
    private val pingProfile: suspend (Profile) -> LatencyResult,
    private val switchProfile: suspend (String) -> Unit,
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

            is Action.ConnectionStateChanged -> {
                dispatch(
                    Msg.ConnectionChanged(
                        action.state,
                        action.connectedSinceMillis
                    )
                )
                if (!action.state.isActiveTunnel) applyPendingSwitch()
            }

            Action.TunnelConnected -> pingActiveProfile()
        }
    }

    override fun executeIntent(intent: DashboardStore.Intent) {
        when (intent) {
            DashboardStore.Intent.ToggleConnection -> toggleConnection()
            is DashboardStore.Intent.SelectProfile -> selectProfile(intent.id)
            DashboardStore.Intent.ConfirmSwitch -> confirmSwitch()
            DashboardStore.Intent.CancelSwitch -> dispatch(Msg.SwitchCleared)
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

    private fun selectProfile(id: String) {
        val s = state()
        when {
            id == s.activeProfileId -> dispatch(Msg.SwitchCleared)
            s.connectionState.isActiveTunnel -> dispatch(Msg.SwitchRequested(id))
            else -> scope.launch { setActiveProfile(id) }
        }
    }

    private fun confirmSwitch() {
        val target = state().pendingSwitchProfile ?: return
        dispatch(Msg.SwitchCleared)
        scope.launch {
            setActiveProfile(target.id)
            runCatching { switchProfile(target.id) }
        }
    }

    private fun applyPendingSwitch() {
        val id = state().pendingSwitchProfileId ?: return
        dispatch(Msg.SwitchCleared)
        scope.launch { setActiveProfile(id) }
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
            val batch = importProfile.importAll(trimmed, format, name.trim().ifEmpty { null })
            dispatch(batch.toMsg())
            val first = batch.imported.firstOrNull()
            if (first != null && state().activeProfileId == null) setActiveProfile(first.id)
        }
    }

    private fun ProfileImportBatch.toMsg(): Msg {
        val failure = failures.singleOrNull()
        return when {
            total == 1 && failure is ProfileImportFailure.Duplicate ->
                Msg.ImportRejectedAsDuplicate(failure.existingName)

            total == 1 && failure is ProfileImportFailure.Invalid -> Msg.ImportFailed(failure.message)
            total == 1 -> Msg.ImportSucceeded
            imported.isEmpty() -> Msg.LinksFailed(failures)
            else -> Msg.LinksImported(imported.size, total, failures)
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
