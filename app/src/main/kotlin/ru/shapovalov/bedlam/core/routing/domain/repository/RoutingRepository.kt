package ru.shapovalov.bedlam.core.routing.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

interface RoutingRepository {
    fun observe(): Flow<RoutingConfig>
    suspend fun get(): RoutingConfig

    suspend fun setBypassLan(enabled: Boolean)
    suspend fun setIpv6Mode(mode: Ipv6Mode)
    suspend fun setDnsMode(mode: DnsMode)
    suspend fun setCustomDns(servers: List<String>)

    suspend fun upsertSource(source: DirectRouteSource)
    suspend fun removeSource(id: String)
    suspend fun setSourceEnabled(id: String, enabled: Boolean)

    /** True iff a source with the same [DirectRouteSource.dedupeKey] already exists. */
    suspend fun hasEquivalent(source: DirectRouteSource): Boolean
    suspend fun recordResolution(sourceId: String, cidrs: List<Cidr>, error: String?)
}
