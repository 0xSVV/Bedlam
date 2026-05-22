package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddPresetUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddRouteSourceUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.RefreshRouteSourcesUseCase

@Inject
class RoutingStoreFactory(
    private val storeFactory: StoreFactory,
    private val routingRepository: RoutingRepository,
    private val addSource: AddRouteSourceUseCase,
    private val addPreset: AddPresetUseCase,
    private val refreshAll: RefreshRouteSourcesUseCase,
) {
    fun create(): RoutingStore =
        object : RoutingStore, Store<RoutingStore.Intent, RoutingStore.State, Nothing>
        by storeFactory.create(
            name = "RoutingStore",
            initialState = RoutingStore.State(),
            bootstrapper = RoutingBootstrapper(routingRepository),
            executorFactory = { RoutingExecutor(routingRepository, addSource, addPreset, refreshAll) },
            reducer = RoutingReducer,
        ) {}
}
