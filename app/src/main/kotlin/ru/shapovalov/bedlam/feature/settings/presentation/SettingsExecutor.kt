package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository

internal class SettingsExecutor(
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
    private val reliabilityRefreshMillis: Long,
) : CoroutineExecutor<SettingsStore.Intent, Action, SettingsStore.State, Msg, Nothing>() {

    private var foreground = false
    private var reliabilityJob: Job? = null

    override fun executeAction(action: Action) {
        when (action) {
            is Action.QuickSettingsTileAddedChanged ->
                dispatch(Msg.QuickSettingsTileAddedChanged(action.added))

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

            is SettingsStore.Intent.SetForeground -> {
                foreground = intent.foreground
                restartReliabilityJob()
            }
        }
    }

    private fun restartReliabilityJob() {
        reliabilityJob?.cancel()
        reliabilityJob = if (foreground) {
            scope.launch {
                while (true) {
                    loadReliabilitySnapshot()
                    delay(reliabilityRefreshMillis)
                }
            }
        } else {
            null
        }
    }

    private suspend fun loadReliabilitySnapshot() {
        dispatch(Msg.ReliabilitySnapshotChanged(powerReliabilityRepository.snapshot()))
    }
}
