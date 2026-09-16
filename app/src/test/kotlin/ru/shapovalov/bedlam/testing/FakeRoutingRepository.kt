package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.hysteria.api.DnsTransport

class FakeRoutingRepository(
    initial: RoutingConfig = RoutingConfig(),
) : RoutingRepository {

    val config = MutableStateFlow(initial)
    var resolvedAtMillis = 1_000L

    override fun observe(): Flow<RoutingConfig> = config

    override suspend fun get(): RoutingConfig = config.value

    override suspend fun setBypassLan(enabled: Boolean) =
        config.update { it.copy(bypassLan = enabled) }

    override suspend fun setIpv6Mode(mode: Ipv6Mode) =
        config.update { it.copy(ipv6Mode = mode) }

    override suspend fun setDnsMode(mode: DnsMode) =
        config.update { it.copy(dnsMode = mode) }

    override suspend fun setDnsTransport(transport: DnsTransport) =
        config.update { it.copy(dnsTransport = transport) }

    override suspend fun setCustomDns(servers: List<String>) =
        config.update { it.copy(customDns = servers) }

    override suspend fun setMtu(mtu: Int) =
        config.update { it.copy(mtu = mtu) }

    override suspend fun upsertSource(source: DirectRouteSource) = updateSources { sources ->
        sources.filterNot { it.source.id == source.id } +
                ResolvedSource(source, emptyList(), null, null)
    }

    override suspend fun removeSource(id: String) = updateSources { sources ->
        sources.filterNot { it.source.id == id }
    }

    override suspend fun setSourceEnabled(id: String, enabled: Boolean) = updateSource(id) {
        it.copy(source = it.source.withEnabled(enabled))
    }

    override suspend fun hasEquivalent(source: DirectRouteSource): Boolean =
        config.value.sources.any { it.source.dedupeKey() == source.dedupeKey() }

    override suspend fun recordResolution(sourceId: String, cidrs: List<Cidr>, error: String?) =
        updateSource(sourceId) {
            it.copy(cidrs = cidrs, lastResolvedMillis = resolvedAtMillis, lastError = error)
        }

    override suspend fun recordResolutionError(sourceId: String, error: String?) =
        updateSource(sourceId) { it.copy(lastError = error) }

    private fun updateSources(transform: (List<ResolvedSource>) -> List<ResolvedSource>) =
        config.update { current ->
            current.copy(
                sources = transform(current.sources)
                    .sortedWith(compareBy({ it.source.orderIndex }, { it.source.id })),
            )
        }

    private fun updateSource(id: String, transform: (ResolvedSource) -> ResolvedSource) =
        updateSources { sources ->
            sources.map { if (it.source.id == id) transform(it) else it }
        }

    private fun DirectRouteSource.withEnabled(enabled: Boolean): DirectRouteSource = when (this) {
        is DirectRouteSource.Cidr -> copy(enabled = enabled)
        is DirectRouteSource.Asn -> copy(enabled = enabled)
        is DirectRouteSource.Domain -> copy(enabled = enabled)
    }
}
