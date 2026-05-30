package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository

internal class SettingsExecutor(
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
) : CoroutineExecutor<SettingsStore.Intent, Action, SettingsStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.QuickSettingsTileAddedChanged ->
                dispatch(Msg.QuickSettingsTileAddedChanged(action.added))
            is Action.ReliabilitySnapshotChanged ->
                dispatch(Msg.ReliabilitySnapshotChanged(action.snapshot))
            is Action.ConfirmedReliabilityFingerprintChanged ->
                dispatch(Msg.ConfirmedReliabilityFingerprintChanged(action.fingerprint))
        }
    }

    override fun executeIntent(intent: SettingsStore.Intent) {
        when (intent) {
            is SettingsStore.Intent.SetQuickSettingsTileAdded -> scope.launch {
                quickSettingsTileRepository.setAdded(intent.added)
            }
            is SettingsStore.Intent.MarkReliabilityConfirmed -> scope.launch {
                powerReliabilityRepository.markConfirmed(intent.fingerprint)
            }
        }
    }
}
