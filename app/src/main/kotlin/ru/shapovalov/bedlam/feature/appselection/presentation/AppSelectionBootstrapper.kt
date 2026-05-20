package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.GetInstalledAppsUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ObserveAppFilterUseCase

internal sealed interface Action {
    data class FilterLoaded(val mode: AppFilterMode, val packages: Set<String>) : Action
    data class AppsLoaded(val apps: List<InstalledApp>) : Action
}

internal class AppSelectionBootstrapper(
    private val observeAppFilter: ObserveAppFilterUseCase,
    private val getInstalledApps: GetInstalledAppsUseCase,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            observeAppFilter().collect { filter ->
                dispatch(Action.FilterLoaded(filter.mode, filter.packages))
            }
        }
        scope.launch {
            dispatch(Action.AppsLoaded(getInstalledApps()))
        }
    }
}
