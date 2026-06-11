package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.update.domain.model.DownloadEvent
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller
import ru.shapovalov.bedlam.feature.update.domain.usecase.DownloadUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.SkipUpdateUseCase

internal class UpdateExecutor(
    private val downloadUpdate: DownloadUpdateUseCase,
    private val skipUpdate: SkipUpdateUseCase,
    private val installer: UpdateInstaller,
) : CoroutineExecutor<UpdateStore.Intent, Action, UpdateStore.State, Msg, UpdateStore.Label>() {

    private var downloadJob: Job? = null

    override fun executeAction(action: Action) {
        when (action) {
            is Action.InstallStatusChanged -> when (val status = action.status) {
                InstallStatus.Idle -> Unit
                InstallStatus.InProgress -> dispatch(Msg.Installing)
                is InstallStatus.Failed -> dispatch(Msg.Failed(status.message))
            }
        }
    }

    override fun executeIntent(intent: UpdateStore.Intent) {
        when (intent) {
            UpdateStore.Intent.Install -> startDownload()
            UpdateStore.Intent.Skip -> skip()
        }
    }

    private fun startDownload() {
        if (downloadJob?.isActive == true) return
        val update = state().update
        dispatch(Msg.Downloading(downloadedBytes = 0, totalBytes = update.sizeBytes))
        downloadJob = scope.launch {
            try {
                downloadUpdate(update).conflate().collect { event ->
                    when (event) {
                        is DownloadEvent.Progress ->
                            dispatch(Msg.Downloading(event.downloadedBytes, event.totalBytes))

                        is DownloadEvent.Completed -> {
                            dispatch(Msg.Installing)
                            installer.install(event.file)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dispatch(Msg.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun skip() {
        scope.launch {
            runCatching { skipUpdate(state().update) }
            publish(UpdateStore.Label.Dismiss)
        }
    }
}
