package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpUpdater
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

@Inject
class RoutingStoreFactory(
    private val storeFactory: StoreFactory,
    private val routingRepository: RoutingRepository,
    private val geoIpDatabase: GeoIpDatabase,
    private val geoIpUpdater: GeoIpUpdater,
) {
    fun create(): RoutingStore =
        object : RoutingStore, Store<RoutingStore.Intent, RoutingStore.State, Nothing>
        by storeFactory.create(
            name = "RoutingStore",
            initialState = RoutingStore.State(),
            bootstrapper = RoutingBootstrapper(routingRepository, geoIpDatabase, geoIpUpdater),
            executorFactory = { RoutingExecutor(routingRepository, geoIpDatabase, geoIpUpdater) },
            reducer = RoutingReducer,
        ) {}
}
