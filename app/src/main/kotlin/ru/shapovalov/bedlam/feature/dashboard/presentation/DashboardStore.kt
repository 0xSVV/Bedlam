package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.hysteria.ConnectionState

interface DashboardStore :
    Store<DashboardStore.Intent, DashboardStore.State, DashboardStore.Label> {

    sealed interface Intent {
        data object ToggleConnection : Intent
        data class SelectProfile(val id: String) : Intent
        data class DeleteProfile(val id: String) : Intent
        data class OpenImport(val prefill: String) : Intent
        data object CloseImport : Intent
        data class ImportProfile(
            val format: ProfileImportFormat,
            val text: String,
            val name: String,
        ) : Intent

        data object DismissError : Intent
        data class PingProfile(val id: String) : Intent
        data object PingAllProfiles : Intent
    }

    data class State(
        val profiles: List<Profile> = emptyList(),
        val activeProfileId: String? = null,
        val connectionState: ConnectionState = ConnectionState.Disconnected(),
        val connectedSinceMillis: Long? = null,
        val importSheet: ImportSheetSeed? = null,
        val isImporting: Boolean = false,
        val importError: String? = null,
        val error: ErrorReason? = null,
        val latencies: Map<String, LatencyResult> = emptyMap(),
    ) {
        val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileId }
    }

    data class ImportSheetSeed(val text: String, val format: ProfileImportFormat)

    sealed interface ErrorReason {
        data object NoActiveProfile : ErrorReason
    }

    sealed interface Label {
        data class RequestStartVpn(val profile: Profile) : Label
        data object RequestStopVpn : Label
    }
}
