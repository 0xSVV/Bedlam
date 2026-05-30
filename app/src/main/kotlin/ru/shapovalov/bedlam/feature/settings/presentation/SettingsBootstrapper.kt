package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository

internal sealed interface Action {
    data class QuickSettingsTileAddedChanged(val added: Boolean) : Action
    data class ReliabilitySnapshotChanged(val snapshot: PowerReliabilitySnapshot) : Action
    data class ConfirmedReliabilityFingerprintChanged(val fingerprint: String?) : Action
}

internal class SettingsBootstrapper(
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
    private val refreshIntervalMillis: Long,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            quickSettingsTileRepository.added.collect {
                dispatch(Action.QuickSettingsTileAddedChanged(it))
            }
        }
        scope.launch {
            powerReliabilityRepository.observeSnapshot(refreshIntervalMillis).collect {
                dispatch(Action.ReliabilitySnapshotChanged(it))
            }
        }
        scope.launch {
            powerReliabilityRepository.confirmedFingerprint.collect {
                dispatch(Action.ConfirmedReliabilityFingerprintChanged(it))
            }
        }
    }
}
