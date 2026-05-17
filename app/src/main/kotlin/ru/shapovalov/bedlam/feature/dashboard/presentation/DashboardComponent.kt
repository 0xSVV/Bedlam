package ru.shapovalov.bedlam.feature.dashboard.presentation

import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.profile.domain.model.Profile

interface DashboardComponent {

    val state: StateFlow<DashboardStore.State>

    fun onToggleConnection()
    fun onSelectProfile(id: String)
    fun onDeleteProfile(id: String)
    fun onImportFromClipboard(uri: String)
    fun onDismissError()

    fun interface OnStartVpn { fun invoke(profile: Profile) }
    fun interface OnStopVpn { fun invoke() }
}
