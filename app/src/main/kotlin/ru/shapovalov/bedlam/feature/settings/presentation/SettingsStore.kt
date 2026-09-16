package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot

interface SettingsStore : Store<SettingsStore.Intent, SettingsStore.State, Nothing> {

    sealed interface Intent {
        data class SetQuickSettingsTileAdded(val added: Boolean) : Intent
        data class MarkReliabilityConfirmed(val fingerprint: String) : Intent
        data class SetForeground(val foreground: Boolean) : Intent
        data class SetReliabilityVisible(val visible: Boolean) : Intent
    }

    data class State(
        val quickSettingsTileAdded: Boolean = false,
        val reliabilitySnapshot: PowerReliabilitySnapshot? = null,
        val confirmedReliabilityFingerprint: String? = null,
    )
}
