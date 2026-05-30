package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot

internal sealed interface Msg {
    data class QuickSettingsTileAddedChanged(val added: Boolean) : Msg
    data class ReliabilitySnapshotChanged(val snapshot: PowerReliabilitySnapshot) : Msg
    data class ConfirmedReliabilityFingerprintChanged(val fingerprint: String?) : Msg
}

internal object SettingsReducer : Reducer<SettingsStore.State, Msg> {
    override fun SettingsStore.State.reduce(msg: Msg): SettingsStore.State = when (msg) {
        is Msg.QuickSettingsTileAddedChanged -> copy(quickSettingsTileAdded = msg.added)
        is Msg.ReliabilitySnapshotChanged -> copy(reliabilitySnapshot = msg.snapshot)
        is Msg.ConfirmedReliabilityFingerprintChanged ->
            copy(confirmedReliabilityFingerprint = msg.fingerprint)
    }
}
