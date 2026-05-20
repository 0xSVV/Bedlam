package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.GetInstalledAppsUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ObserveAppFilterUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.SetAppFilterModeUseCase
import ru.shapovalov.bedlam.core.appfilter.domain.usecase.ToggleAppFilterPackageUseCase

@Inject
class AppSelectionStoreFactory(
    private val storeFactory: StoreFactory,
    private val observeAppFilter: ObserveAppFilterUseCase,
    private val getInstalledApps: GetInstalledAppsUseCase,
    private val setMode: SetAppFilterModeUseCase,
    private val togglePackage: ToggleAppFilterPackageUseCase,
) {
    fun create(): AppSelectionStore =
        object : AppSelectionStore, Store<AppSelectionStore.Intent, AppSelectionStore.State, Nothing>
        by storeFactory.create(
            name = "AppSelectionStore",
            initialState = AppSelectionStore.State(),
            bootstrapper = AppSelectionBootstrapper(observeAppFilter, getInstalledApps),
            executorFactory = { AppSelectionExecutor(setMode, togglePackage) },
            reducer = AppSelectionReducer,
        ) {}
}
