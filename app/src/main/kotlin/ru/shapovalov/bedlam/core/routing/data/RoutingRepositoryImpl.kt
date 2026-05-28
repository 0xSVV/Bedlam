package ru.shapovalov.bedlam.core.routing.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.data.local.ResolvedCidrEntity
import ru.shapovalov.bedlam.core.routing.data.local.RouteSourceEntity
import ru.shapovalov.bedlam.core.routing.data.local.RoutingConfigEntity
import ru.shapovalov.bedlam.core.routing.data.local.RoutingDao
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

@Inject
class RoutingRepositoryImpl(
    private val dao: RoutingDao,
) : RoutingRepository {

    private val mutex = Mutex()

    override fun observe(): Flow<RoutingConfig> =
        combine(
            dao.observeConfig().distinctUntilChanged(),
            dao.observeSources().distinctUntilChanged(),
            dao.observeAllResolved().distinctUntilChanged(),
        ) { cfg, sources, resolved ->
            buildConfig(cfg, sources, resolved)
        }

    override suspend fun get(): RoutingConfig {
        val cfg = dao.getConfig()
        val sources = dao.getSources()
        val resolved = sources.flatMap { dao.getResolvedFor(it.id) }
        return buildConfig(cfg, sources, resolved)
    }

    override suspend fun setBypassLan(enabled: Boolean) = mutex.withLock {
        val current = dao.getConfig() ?: RoutingConfigEntity()
        dao.upsertConfig(current.copy(bypassLan = enabled))
    }

    override suspend fun setIpv6Mode(mode: Ipv6Mode) = mutex.withLock {
        val current = dao.getConfig() ?: RoutingConfigEntity()
        dao.upsertConfig(current.copy(ipv6Mode = mode.name))
    }

    override suspend fun setDnsMode(mode: DnsMode) = mutex.withLock {
        val current = dao.getConfig() ?: RoutingConfigEntity()
        dao.upsertConfig(current.copy(dnsMode = mode.name))
    }

    override suspend fun setCustomDns(servers: List<String>) = mutex.withLock {
        val current = dao.getConfig() ?: RoutingConfigEntity()
        dao.upsertConfig(current.copy(customDnsCsv = servers.joinToString(",")))
    }

    override suspend fun upsertSource(source: DirectRouteSource) {
        dao.upsertSource(source.toEntity())
    }

    override suspend fun removeSource(id: String) {
        dao.deleteSource(id)
    }

    override suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        dao.setSourceEnabled(id, enabled)
    }

    override suspend fun hasEquivalent(source: DirectRouteSource): Boolean {
        val key = source.dedupeKey()
        return dao.getSources().any { it.toDomainOrNull()?.dedupeKey() == key }
    }

    override suspend fun recordResolution(sourceId: String, cidrs: List<Cidr>, error: String?) {
        dao.replaceResolved(sourceId, cidrs.map { it.asString() })
        dao.setSourceResolutionState(sourceId, System.currentTimeMillis(), error)
    }

    private fun buildConfig(
        cfg: RoutingConfigEntity?,
        sources: List<RouteSourceEntity>,
        resolved: List<ResolvedCidrEntity>,
    ): RoutingConfig {
        val resolvedBySource = resolved.groupBy { it.sourceId }
        val domain = sources.mapNotNull { s ->
            val src = s.toDomainOrNull() ?: return@mapNotNull null
            val cidrs = resolvedBySource[s.id].orEmpty()
                .mapNotNull { Cidr.parseOrNull(it.cidr) }
            ResolvedSource(
                source = src,
                cidrs = cidrs,
                lastResolvedMillis = s.lastResolvedMillis,
                lastError = s.lastError,
            )
        }
        val configEntity = cfg ?: RoutingConfigEntity()
        return RoutingConfig(
            bypassLan = configEntity.bypassLan,
            ipv6Mode = runCatching { Ipv6Mode.valueOf(configEntity.ipv6Mode) }
                .getOrDefault(Ipv6Mode.Enabled),
            dnsMode = runCatching { DnsMode.valueOf(configEntity.dnsMode) }
                .getOrDefault(DnsMode.Cloudflare),
            customDns = configEntity.customDnsCsv.toCsvList(),
            sources = domain,
        )
    }
}

private fun RouteSourceEntity.toDomainOrNull(): DirectRouteSource? = when (kind) {
    RouteSourceEntity.KIND_CIDR -> Cidr.parseOrNull(rawValue)?.let {
        DirectRouteSource.Cidr(id, it, comment, enabled, orderIndex)
    }

    RouteSourceEntity.KIND_ASN -> rawValue.toIntOrNull()?.let {
        DirectRouteSource.Asn(id, it, comment, enabled, orderIndex)
    }

    RouteSourceEntity.KIND_DOMAIN -> DirectRouteSource.Domain(
        id = id,
        hostname = rawValue,
        comment = comment,
        enabled = enabled,
        orderIndex = orderIndex,
    )

    else -> null
}

private fun DirectRouteSource.toEntity(): RouteSourceEntity = when (this) {
    is DirectRouteSource.Cidr -> RouteSourceEntity(
        id = id,
        kind = RouteSourceEntity.KIND_CIDR,
        rawValue = cidr.asString(),
        comment = comment,
        enabled = enabled,
        orderIndex = orderIndex,
        lastResolvedMillis = null,
        lastError = null,
    )

    is DirectRouteSource.Asn -> RouteSourceEntity(
        id = id,
        kind = RouteSourceEntity.KIND_ASN,
        rawValue = asn.toString(),
        comment = comment,
        enabled = enabled,
        orderIndex = orderIndex,
        lastResolvedMillis = null,
        lastError = null,
    )

    is DirectRouteSource.Domain -> RouteSourceEntity(
        id = id,
        kind = RouteSourceEntity.KIND_DOMAIN,
        rawValue = hostname,
        comment = comment,
        enabled = enabled,
        orderIndex = orderIndex,
        lastResolvedMillis = null,
        lastError = null,
    )
}

private fun String.toCsvList(): List<String> =
    if (isEmpty()) emptyList() else split(',').filter { it.isNotEmpty() }
