package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository
import ru.shapovalov.bedlam.feature.update.domain.usecase.ObserveAvailableVersionUseCase

internal sealed interface Action {
    data class QuickSettingsTileAddedChanged(val added: Boolean) : Action
    data class ConfirmedReliabilityFingerprintChanged(val fingerprint: String?) : Action
    data class AvailableVersionChanged(val version: String?) : Action
}

internal class SettingsBootstrapper(
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
    private val observeAvailableVersion: ObserveAvailableVersionUseCase,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            quickSettingsTileRepository.added.collect {
                dispatch(Action.QuickSettingsTileAddedChanged(it))
            }
        }
        scope.launch {
            powerReliabilityRepository.confirmedFingerprint.collect {
                dispatch(Action.ConfirmedReliabilityFingerprintChanged(it))
            }
        }
        scope.launch {
            observeAvailableVersion().collect {
                dispatch(Action.AvailableVersionChanged(it))
            }
        }
    }
}
