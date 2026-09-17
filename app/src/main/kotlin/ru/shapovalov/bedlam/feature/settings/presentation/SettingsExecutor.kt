package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.vpn.tile.domain.repository.QuickSettingsTileRepository
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsStore.State.UpdateCheck
import ru.shapovalov.bedlam.feature.update.domain.usecase.FetchUpdateUseCase

internal class SettingsExecutor(
    private val powerReliabilityRepository: PowerReliabilityRepository,
    private val quickSettingsTileRepository: QuickSettingsTileRepository,
    private val fetchUpdate: FetchUpdateUseCase,
    private val reliabilityRefreshMillis: Long,
) : CoroutineExecutor<SettingsStore.Intent, Action, SettingsStore.State, Msg, SettingsStore.Label>() {

    private var foreground = false
    private var reliabilityVisible = false
    private var reliabilityJob: Job? = null

    override fun executeAction(action: Action) {
        when (action) {
            is Action.QuickSettingsTileAddedChanged ->
                dispatch(Msg.QuickSettingsTileAddedChanged(action.added))

            is Action.ConfirmedReliabilityFingerprintChanged ->
                dispatch(Msg.ConfirmedReliabilityFingerprintChanged(action.fingerprint))

            is Action.AvailableVersionChanged ->
                dispatch(Msg.AvailableVersionChanged(action.version))
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

            is SettingsStore.Intent.SetReliabilityVisible -> {
                reliabilityVisible = intent.visible
                restartReliabilityJob()
            }

            SettingsStore.Intent.CheckForUpdates -> checkForUpdates()
        }
    }

    private fun checkForUpdates() {
        if (state().updateCheck == UpdateCheck.Checking) return
        dispatch(Msg.UpdateCheckChanged(UpdateCheck.Checking))
        scope.launch {
            val update = try {
                fetchUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                dispatch(Msg.UpdateCheckChanged(UpdateCheck.Failed))
                return@launch
            }
            if (update == null) {
                dispatch(Msg.UpdateCheckChanged(UpdateCheck.UpToDate))
            } else {
                dispatch(Msg.UpdateCheckChanged(UpdateCheck.Idle))
                publish(SettingsStore.Label.OpenUpdate(update))
            }
        }
    }

    private fun restartReliabilityJob() {
        reliabilityJob?.cancel()
        reliabilityJob = when {
            foreground && reliabilityVisible -> scope.launch {
                while (true) {
                    loadReliabilitySnapshot()
                    delay(reliabilityRefreshMillis)
                }
            }

            foreground || state().reliabilitySnapshot == null ->
                scope.launch { loadReliabilitySnapshot() }

            else -> null
        }
    }

    private suspend fun loadReliabilitySnapshot() {
        dispatch(Msg.ReliabilitySnapshotChanged(powerReliabilityRepository.snapshot()))
    }
}
