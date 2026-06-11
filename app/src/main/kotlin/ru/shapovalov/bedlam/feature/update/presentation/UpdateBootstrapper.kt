package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateInstaller

internal sealed interface Action {
    data class InstallStatusChanged(val status: InstallStatus) : Action
}

internal class UpdateBootstrapper(
    private val installer: UpdateInstaller,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        installer.reset()
        scope.launch {
            installer.status.collect { status ->
                dispatch(Action.InstallStatusChanged(status))
            }
        }
    }
}
