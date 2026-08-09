package ru.shapovalov.bedlam.feature.update.domain.model

sealed interface InstallStatus {
    data object Idle : InstallStatus
    data object InProgress : InstallStatus
    data object NeedsInstallPermission : InstallStatus
    data object SignatureMismatch : InstallStatus
    data class Failed(val message: String) : InstallStatus
}
