package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate

interface SettingsStore : Store<SettingsStore.Intent, SettingsStore.State, SettingsStore.Label> {

    sealed interface Intent {
        data class SetQuickSettingsTileAdded(val added: Boolean) : Intent
        data class MarkReliabilityConfirmed(val fingerprint: String) : Intent
        data class SetForeground(val foreground: Boolean) : Intent
        data class SetReliabilityVisible(val visible: Boolean) : Intent
        data object CheckForUpdates : Intent
    }

    sealed interface Label {
        data class OpenUpdate(val update: AppUpdate) : Label
    }

    data class State(
        val quickSettingsTileAdded: Boolean = false,
        val reliabilitySnapshot: PowerReliabilitySnapshot? = null,
        val confirmedReliabilityFingerprint: String? = null,
        val availableVersion: String? = null,
        val updateCheck: UpdateCheck = UpdateCheck.Idle,
    ) {
        enum class UpdateCheck { Idle, Checking, UpToDate, Failed }
    }
}
