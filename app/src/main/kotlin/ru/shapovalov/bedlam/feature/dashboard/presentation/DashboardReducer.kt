package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.hysteria.ConnectionState

internal sealed interface Msg {
    data class ProfilesLoaded(val profiles: List<Profile>, val activeId: String?) : Msg
    data class ConnectionChanged(val state: ConnectionState, val connectedSinceMillis: Long?) : Msg
    data object ImportStarted : Msg
    data object ImportFinished : Msg
    data class ErrorRaised(val reason: DashboardStore.ErrorReason) : Msg
    data object ErrorDismissed : Msg
    data class LatencyUpdated(val id: String, val result: LatencyResult) : Msg
}

internal object DashboardReducer : Reducer<DashboardStore.State, Msg> {
    override fun DashboardStore.State.reduce(msg: Msg): DashboardStore.State = when (msg) {
        is Msg.ProfilesLoaded -> copy(profiles = msg.profiles, activeProfileId = msg.activeId)
        is Msg.ConnectionChanged -> copy(
            connectionState = msg.state,
            connectedSinceMillis = msg.connectedSinceMillis,
        )

        Msg.ImportStarted -> copy(isImporting = true, error = null)
        Msg.ImportFinished -> copy(isImporting = false)
        is Msg.ErrorRaised -> copy(error = msg.reason)
        Msg.ErrorDismissed -> copy(error = null)
        is Msg.LatencyUpdated -> copy(latencies = latencies + (msg.id to msg.result))
    }
}
