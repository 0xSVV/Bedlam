package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpDatabaseInfo
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteRule
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

interface RoutingStore : Store<RoutingStore.Intent, RoutingStore.State, Nothing> {

    sealed interface Intent {
        data class SetBypassLan(val enabled: Boolean) : Intent
        data class SetIpv6Mode(val mode: Ipv6Mode) : Intent
        data class SetDnsMode(val mode: DnsMode) : Intent
        data class SetCustomDns(val servers: List<String>) : Intent
        data class UpsertDirectRoute(val rule: DirectRouteRule) : Intent
        data class RemoveDirectRoute(val id: String) : Intent
        data class SetDirectRouteEnabled(val id: String, val enabled: Boolean) : Intent
        data class ToggleGeoCountry(val country: CountryCode) : Intent
        data object DownloadGeoIp : Intent
        data object RemoveGeoIp : Intent
    }

    data class State(
        val config: RoutingConfig = RoutingConfig(),
        val geoIpInfo: GeoIpDatabaseInfo = GeoIpDatabaseInfo.Empty,
        val geoIpUpdateState: GeoIpUpdateState = GeoIpUpdateState.Idle,
        val availableCountries: List<CountryCode> = emptyList(),
    )
}
