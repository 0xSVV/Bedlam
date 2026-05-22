package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpDatabaseInfo
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpUpdater
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

internal sealed interface Action {
    data class ConfigChanged(val config: RoutingConfig) : Action
    data class GeoIpInfoChanged(val info: GeoIpDatabaseInfo) : Action
    data class GeoIpUpdateStateChanged(val state: GeoIpUpdateState) : Action
    data class CountriesLoaded(val countries: List<CountryCode>) : Action
}

internal class RoutingBootstrapper(
    private val routingRepository: RoutingRepository,
    private val geoIpDatabase: GeoIpDatabase,
    private val geoIpUpdater: GeoIpUpdater,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            routingRepository.observe().collect { dispatch(Action.ConfigChanged(it)) }
        }
        scope.launch {
            geoIpUpdater.observeInfo().collect { dispatch(Action.GeoIpInfoChanged(it)) }
        }
        scope.launch {
            geoIpUpdater.state.collect { dispatch(Action.GeoIpUpdateStateChanged(it)) }
        }
        scope.launch {
            dispatch(Action.CountriesLoaded(geoIpDatabase.availableCountries()))
        }
    }
}
