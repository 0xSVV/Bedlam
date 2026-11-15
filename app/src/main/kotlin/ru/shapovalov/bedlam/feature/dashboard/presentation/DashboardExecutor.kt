package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val pingSlots = Semaphore(3)
    private var pendingConnectedPing = false

    override fun executeAction(action: Action) {
        when (action) {
            is Action.ProfilesLoaded -> {
                cancelPingsOfChangedProfiles(action.profiles)
                dispatch(
                    Msg.ProfilesLoaded(
                        action.profiles,
                        action.activeId
                    )
                )
                if (pendingConnectedPing) pingActiveProfile()
            }

            is Action.ConnectionStateChanged -> {
                val changed = state().connectionState != action.state
                if (changed) invalidatePings()
                if (action.state !is ConnectionState.Connected) {
                    pendingConnectedPing = false
                } else if (changed) {
                    pendingConnectedPing = true
                }
                dispatch(
                    Msg.ConnectionChanged(
                        action.state,
                        action.connectedSinceMillis
                    )
                )
                if (pendingConnectedPing) pingActiveProfile()
                if (!action.state.isActiveTunnel) applyPendingSwitch()
            }
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
        when (s.displayedConnectionState) {
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
        dispatch(Msg.SwitchConfirmed)
        scope.launch {
            setActiveProfile(target.id)
            runCatching { switchProfile(target.id) }.onFailure {
                if (it is CancellationException) throw it
                android.util.Log.w("DashboardExecutor", "Profile switch failed", it)
            }
            dispatch(Msg.SwitchFinished)
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
        pingJobs.remove(profile.id)?.cancel()
        dispatch(Msg.LatencyUpdated(profile.id, LatencyResult.Measuring))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = pingSlots.withPermit { pingProfile(profile) }
                currentCoroutineContext().ensureActive()
                if (state().profiles.any { it.id == profile.id && it.config == profile.config }) {
                    dispatch(Msg.LatencyUpdated(profile.id, result))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (state().profiles.any { it.id == profile.id && it.config == profile.config }) {
                    dispatch(Msg.LatencyUpdated(profile.id, LatencyResult.Unreachable))
                }
            } finally {
                if (pingJobs[profile.id] == currentCoroutineContext()[Job]) pingJobs.remove(profile.id)
            }
        }
        pingJobs[profile.id] = job
        job.start()
    }

    private fun cancelPingsOfChangedProfiles(profiles: List<Profile>) {
        val previous = state().profiles.associateBy { it.id }
        val unchanged = profiles.mapNotNullTo(HashSet()) { next ->
            next.id.takeIf { previous[it]?.config == next.config }
        }
        (pingJobs.keys - unchanged).forEach { id -> pingJobs.remove(id)?.cancel() }
    }

    private fun invalidatePings() {
        val cancelled = pingJobs.values.toList()
        pingJobs.clear()
        cancelled.forEach(Job::cancel)
        dispatch(Msg.LatenciesCleared)
    }

    private fun pingActiveProfile() {
        val profile = state().activeProfile ?: return
        pendingConnectedPing = false
        measure(profile)
    }
}
