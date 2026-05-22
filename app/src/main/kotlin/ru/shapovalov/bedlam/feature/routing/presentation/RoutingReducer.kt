package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpDatabaseInfo
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

internal sealed interface Msg {
    data class ConfigChanged(val config: RoutingConfig) : Msg
    data class GeoIpInfoChanged(val info: GeoIpDatabaseInfo) : Msg
    data class GeoIpUpdateStateChanged(val state: GeoIpUpdateState) : Msg
    data class CountriesLoaded(val countries: List<CountryCode>) : Msg
}

internal object RoutingReducer : Reducer<RoutingStore.State, Msg> {
    override fun RoutingStore.State.reduce(msg: Msg): RoutingStore.State = when (msg) {
        is Msg.ConfigChanged -> copy(config = msg.config)
        is Msg.GeoIpInfoChanged -> copy(geoIpInfo = msg.info)
        is Msg.GeoIpUpdateStateChanged -> copy(geoIpUpdateState = msg.state)
        is Msg.CountriesLoaded -> copy(availableCountries = msg.countries)
    }
}
