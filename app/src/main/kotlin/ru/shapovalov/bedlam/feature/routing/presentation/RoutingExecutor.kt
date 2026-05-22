package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpUpdater
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

internal class RoutingExecutor(
    private val routingRepository: RoutingRepository,
    private val geoIpDatabase: GeoIpDatabase,
    private val geoIpUpdater: GeoIpUpdater,
) : CoroutineExecutor<RoutingStore.Intent, Action, RoutingStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.ConfigChanged -> dispatch(Msg.ConfigChanged(action.config))
            is Action.GeoIpInfoChanged -> dispatch(Msg.GeoIpInfoChanged(action.info))
            is Action.GeoIpUpdateStateChanged -> dispatch(Msg.GeoIpUpdateStateChanged(action.state))
            is Action.CountriesLoaded -> dispatch(Msg.CountriesLoaded(action.countries))
        }
    }

    override fun executeIntent(intent: RoutingStore.Intent) {
        when (intent) {
            is RoutingStore.Intent.SetBypassLan -> scope.launch {
                routingRepository.setBypassLan(intent.enabled)
            }
            is RoutingStore.Intent.SetIpv6Mode -> scope.launch {
                routingRepository.setIpv6Mode(intent.mode)
            }
            is RoutingStore.Intent.SetDnsMode -> scope.launch {
                routingRepository.setDnsMode(intent.mode)
            }
            is RoutingStore.Intent.SetCustomDns -> scope.launch {
                routingRepository.setCustomDns(intent.servers)
            }
            is RoutingStore.Intent.UpsertDirectRoute -> scope.launch {
                routingRepository.upsertDirectRoute(intent.rule)
            }
            is RoutingStore.Intent.RemoveDirectRoute -> scope.launch {
                routingRepository.removeDirectRoute(intent.id)
            }
            is RoutingStore.Intent.SetDirectRouteEnabled -> scope.launch {
                routingRepository.setDirectRouteEnabled(intent.id, intent.enabled)
            }
            is RoutingStore.Intent.ToggleGeoCountry -> scope.launch {
                val current = routingRepository.get().geoDirectCountries
                val next = if (intent.country in current) current - intent.country
                else current + intent.country
                routingRepository.setGeoDirectCountries(next)
            }
            RoutingStore.Intent.DownloadGeoIp -> scope.launch {
                geoIpUpdater.downloadLatest()
                dispatch(Msg.CountriesLoaded(geoIpDatabase.availableCountries()))
            }
            RoutingStore.Intent.RemoveGeoIp -> scope.launch {
                geoIpUpdater.remove()
                dispatch(Msg.CountriesLoaded(emptyList()))
            }
        }
    }
}
