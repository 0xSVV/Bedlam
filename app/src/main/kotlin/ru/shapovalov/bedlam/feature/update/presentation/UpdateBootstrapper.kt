package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository

internal sealed interface Action {
    data class InstallStatusChanged(val status: InstallStatus) : Action
    data object LastReminder : Action
}

internal class UpdateBootstrapper(
    private val update: AppUpdate,
    private val trigger: UpdateTrigger,
    private val repository: UpdateRepository,
    private val installer: UpdateInstaller,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        installer.reset()
        scope.launch {
            installer.status.collect { status ->
                dispatch(Action.InstallStatusChanged(status))
            }
        }
        if (trigger == UpdateTrigger.LaunchCheck) {
            scope.launch {
                if (runCatching { repository.skipsLeft(update.versionName) }.getOrNull() == 1) {
                    dispatch(Action.LastReminder)
                }
            }
        }
    }
}
