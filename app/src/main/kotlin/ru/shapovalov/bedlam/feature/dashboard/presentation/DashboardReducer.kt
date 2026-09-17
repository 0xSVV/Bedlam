package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.isActiveTunnel

internal sealed interface Msg {
    data class ProfilesLoaded(val profiles: List<Profile>, val activeId: String?) : Msg
    data class ConnectionChanged(val state: ConnectionState, val connectedSinceMillis: Long?) : Msg
    data class SwitchRequested(val id: String) : Msg
    data object SwitchCleared : Msg
    data class ImportSheetOpened(val seed: DashboardStore.ImportSheetSeed) : Msg
    data object ImportSheetClosed : Msg
    data object ImportStarted : Msg
    data object ImportSucceeded : Msg
    data class ImportFailed(val message: String) : Msg
    data class ImportRejectedAsDuplicate(val name: String) : Msg
    data class ErrorRaised(val reason: DashboardStore.ErrorReason) : Msg
    data object ErrorDismissed : Msg
    data class LatencyUpdated(val id: String, val result: LatencyResult) : Msg
}

internal object DashboardReducer : Reducer<DashboardStore.State, Msg> {
    override fun DashboardStore.State.reduce(msg: Msg): DashboardStore.State = when (msg) {
        is Msg.ProfilesLoaded -> {
            val ids = msg.profiles.mapTo(HashSet()) { it.id }
            copy(
                profiles = msg.profiles,
                activeProfileId = msg.activeId,
                pendingSwitchProfileId = pendingSwitchProfileId?.takeIf { pending ->
                    pending in ids && pending != msg.activeId
                },
                latencies = latencies.filterKeys { it in ids },
            )
        }

        is Msg.ConnectionChanged -> copy(
            connectionState = msg.state,
            connectedSinceMillis = msg.connectedSinceMillis,
            error = errorAfterConnectionChange(msg.state),
        )

        is Msg.SwitchRequested -> copy(pendingSwitchProfileId = msg.id)
        Msg.SwitchCleared -> copy(pendingSwitchProfileId = null)

        is Msg.ImportSheetOpened -> copy(
            importSheet = msg.seed,
            importSheetClosing = false,
            importError = null,
        )

        Msg.ImportSheetClosed -> copy(importSheet = null, importSheetClosing = false, importError = null)
        Msg.ImportStarted -> copy(isImporting = true, importError = null)
        Msg.ImportSucceeded -> copy(
            isImporting = false,
            importSheetClosing = importSheet != null,
            importError = null,
        )

        is Msg.ImportFailed ->
            if (importSheet != null) {
                copy(isImporting = false, importError = msg.message)
            } else {
                copy(isImporting = false, error = DashboardStore.ErrorReason.ImportFailed(msg.message))
            }

        is Msg.ImportRejectedAsDuplicate -> copy(
            isImporting = false,
            importSheetClosing = importSheet != null,
            importError = null,
            error = DashboardStore.ErrorReason.DuplicateProfile(msg.name),
        )
        is Msg.ErrorRaised -> copy(error = msg.reason)
        Msg.ErrorDismissed -> copy(error = null)
        is Msg.LatencyUpdated ->
            if (profiles.none { it.id == msg.id }) this
            else copy(latencies = latencies + (msg.id to msg.result))
    }

    private fun DashboardStore.State.errorAfterConnectionChange(
        next: ConnectionState,
    ): DashboardStore.ErrorReason? = when {
        next is ConnectionState.Error && connectionState.isActiveTunnel ->
            DashboardStore.ErrorReason.ConnectionFailed(next.message)

        next !is ConnectionState.Error && error is DashboardStore.ErrorReason.ConnectionFailed -> null
        else -> error
    }
}
