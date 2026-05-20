package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.SetAppFilterModeUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ToggleAppFilterPackageUseCase

internal class AppSelectionExecutor(
    private val setMode: SetAppFilterModeUseCase,
    private val togglePackage: ToggleAppFilterPackageUseCase,
) : CoroutineExecutor<AppSelectionStore.Intent, Action, AppSelectionStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.FilterLoaded -> dispatch(Msg.FilterLoaded(action.mode, action.packages))
            is Action.AppsLoaded -> dispatch(Msg.AppsLoaded(action.apps))
        }
    }

    override fun executeIntent(intent: AppSelectionStore.Intent) {
        when (intent) {
            is AppSelectionStore.Intent.ChangeMode -> scope.launch { setMode(intent.mode) }
            is AppSelectionStore.Intent.TogglePackage -> scope.launch { togglePackage(intent.pkg) }
            is AppSelectionStore.Intent.UpdateQuery -> dispatch(Msg.QueryChanged(intent.query))
        }
    }
}
