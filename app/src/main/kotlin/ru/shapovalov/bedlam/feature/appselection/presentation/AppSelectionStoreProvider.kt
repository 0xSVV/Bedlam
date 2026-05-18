package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.GetInstalledAppsUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ObserveAppFilterUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.SetAppFilterModeUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ToggleAppFilterPackageUseCase
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionStore.Intent
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionStore.State

@Inject
class AppSelectionStoreProvider(
    private val storeFactory: StoreFactory,
    private val observeAppFilter: ObserveAppFilterUseCase,
    private val getInstalledApps: GetInstalledAppsUseCase,
    private val setMode: SetAppFilterModeUseCase,
    private val togglePackage: ToggleAppFilterPackageUseCase,
) {

    fun provide(): AppSelectionStore =
        object : AppSelectionStore, Store<Intent, State, Nothing> by storeFactory.create(
            name = "AppSelectionStore",
            initialState = State(),
            bootstrapper = BootstrapperImpl(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        ) {}

    private sealed interface Msg {
        data class FilterLoaded(val mode: AppFilterMode, val packages: Set<String>) : Msg
        data class AppsLoaded(val apps: List<InstalledApp>) : Msg
        data class QueryChanged(val query: String) : Msg
    }

    private sealed interface Action {
        data object LoadApps : Action
        data object ObserveFilter : Action
    }

    private inner class BootstrapperImpl : CoroutineBootstrapper<Action>() {
        override fun invoke() {
            dispatch(Action.ObserveFilter)
            dispatch(Action.LoadApps)
        }
    }

    private inner class ExecutorImpl : CoroutineExecutor<Intent, Action, State, Msg, Nothing>() {

        override fun executeAction(action: Action) {
            when (action) {
                Action.ObserveFilter -> scope.launch {
                    observeAppFilter().collect { filter ->
                        dispatch(Msg.FilterLoaded(filter.mode, filter.packages))
                    }
                }

                Action.LoadApps -> scope.launch {
                    dispatch(Msg.AppsLoaded(getInstalledApps()))
                }
            }
        }

        override fun executeIntent(intent: Intent) {
            when (intent) {
                is Intent.ChangeMode -> scope.launch { setMode(intent.mode) }
                is Intent.TogglePackage -> scope.launch { togglePackage(intent.pkg) }
                is Intent.UpdateQuery -> dispatch(Msg.QueryChanged(intent.query))
            }
        }
    }

    private object ReducerImpl : Reducer<State, Msg> {
        override fun State.reduce(msg: Msg): State = when (msg) {
            is Msg.FilterLoaded -> copy(mode = msg.mode, selectedPackages = msg.packages)
            is Msg.AppsLoaded -> copy(apps = msg.apps, isLoading = false)
            is Msg.QueryChanged -> copy(query = msg.query)
        }
    }
}
