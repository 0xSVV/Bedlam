package ru.shapovalov.bedlam.core.routing.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.data.local.DirectRouteEntity
import ru.shapovalov.bedlam.core.routing.data.local.RoutingConfigEntity
import ru.shapovalov.bedlam.core.routing.data.local.RoutingDao
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteRule
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

@Inject
class RoutingRepositoryImpl(
    private val dao: RoutingDao,
) : RoutingRepository {

    private val mutex = Mutex()

    override fun observe(): Flow<RoutingConfig> =
        combine(dao.observeConfig(), dao.observeDirectRoutes()) { cfg, rules ->
            (cfg ?: RoutingConfigEntity()).toDomain(rules.mapNotNull(DirectRouteEntity::toDomain))
        }

    override suspend fun get(): RoutingConfig {
        val cfg = dao.getConfig() ?: RoutingConfigEntity()
        val rules = dao.getDirectRoutes().mapNotNull(DirectRouteEntity::toDomain)
        return cfg.toDomain(rules)
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

    override suspend fun setGeoDirectCountries(countries: Set<CountryCode>) = mutex.withLock {
        val current = dao.getConfig() ?: RoutingConfigEntity()
        dao.upsertConfig(
            current.copy(geoDirectCountriesCsv = countries.joinToString(",") { it.raw })
        )
    }

    override suspend fun upsertDirectRoute(rule: DirectRouteRule) {
        dao.upsertDirectRoute(rule.toEntity())
    }

    override suspend fun removeDirectRoute(id: String) {
        dao.deleteDirectRoute(id)
    }

    override suspend fun setDirectRouteEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
    }
}

private fun RoutingConfigEntity.toDomain(rules: List<DirectRouteRule>): RoutingConfig =
    RoutingConfig(
        bypassLan = bypassLan,
        ipv6Mode = runCatching { Ipv6Mode.valueOf(ipv6Mode) }.getOrDefault(Ipv6Mode.Enabled),
        dnsMode = runCatching { DnsMode.valueOf(dnsMode) }.getOrDefault(DnsMode.Cloudflare),
        customDns = customDnsCsv.toCsvList(),
        directRoutes = rules,
        geoDirectCountries = geoDirectCountriesCsv.toCsvList()
            .mapNotNull(CountryCode.Companion::ofOrNull)
            .toSet(),
    )

private fun DirectRouteEntity.toDomain(): DirectRouteRule? {
    val parsed = Cidr.parseOrNull(cidr) ?: return null
    return DirectRouteRule(
        id = id,
        cidr = parsed,
        comment = comment,
        enabled = enabled,
        orderIndex = orderIndex,
    )
}

private fun DirectRouteRule.toEntity(): DirectRouteEntity = DirectRouteEntity(
    id = id,
    cidr = cidr.asString(),
    comment = comment,
    enabled = enabled,
    orderIndex = orderIndex,
)

private fun String.toCsvList(): List<String> =
    if (isEmpty()) emptyList() else split(',').filter { it.isNotEmpty() }
