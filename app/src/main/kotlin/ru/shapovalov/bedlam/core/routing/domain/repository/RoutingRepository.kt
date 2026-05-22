package ru.shapovalov.bedlam.core.routing.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteRule
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

interface RoutingRepository {
    fun observe(): Flow<RoutingConfig>
    suspend fun get(): RoutingConfig

    suspend fun setBypassLan(enabled: Boolean)
    suspend fun setIpv6Mode(mode: ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode)
    suspend fun setDnsMode(mode: ru.shapovalov.bedlam.core.routing.domain.model.DnsMode)
    suspend fun setCustomDns(servers: List<String>)
    suspend fun setGeoDirectCountries(countries: Set<ru.shapovalov.bedlam.core.routing.domain.model.CountryCode>)

    suspend fun upsertDirectRoute(rule: DirectRouteRule)
    suspend fun removeDirectRoute(id: String)
    suspend fun setDirectRouteEnabled(id: String, enabled: Boolean)
}
