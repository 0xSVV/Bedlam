package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

/**
 * Builds a [RoutePlan] from a [RoutingConfig] + [AppFilter]. Pure — no Android deps.
 *
 * @property supportsExcludeRoute on API 33+, ships exclusions verbatim; otherwise
 *   subtracts on our side.
 * @property tunPrefixV4 the local TUN v4 prefix; always excluded to avoid loops.
 * @property tunPrefixV6 the local TUN v6 prefix.
 */
class RoutePlanner(
    private val supportsExcludeRoute: Boolean,
    private val tunPrefixV4: Cidr.V4,
    private val tunPrefixV6: Cidr.V6,
    private val geoIp: GeoIpDatabase,
) {

    suspend fun plan(config: RoutingConfig, appFilter: AppFilter): RoutePlan {
        val baseV4 = listOf(IPV4_DEFAULT)
        val baseV6 = if (config.ipv6Mode == Ipv6Mode.Enabled) {
            listOf(IPV6_DEFAULT)
        } else {
            emptyList()
        }

        val systemExclusionsV4 = listOf<Cidr.V4>(tunPrefixV4)
        val systemExclusionsV6 = listOf<Cidr.V6>(tunPrefixV6)

        val lanExclusionsV4 = if (config.bypassLan) LanRanges.IPV4 else emptyList()
        val lanExclusionsV6 = if (config.bypassLan) LanRanges.IPV6 else emptyList()

        val (directV4, directV6) = collectDirectCidrs(config)

        val excludedV4Raw: List<Cidr> = systemExclusionsV4 + lanExclusionsV4 + directV4
        val excludedV6Raw: List<Cidr> = systemExclusionsV6 + lanExclusionsV6 + directV6
        val excludedV4 = CidrMath.coalesce(excludedV4Raw).filterIsInstance<Cidr.V4>()
        val excludedV6 = CidrMath.coalesce(excludedV6Raw).filterIsInstance<Cidr.V6>()

        val dnsServers = resolveDns(config)

        return if (supportsExcludeRoute) {
            RoutePlan(
                claimedV4 = baseV4,
                claimedV6 = baseV6,
                excludedV4 = excludedV4,
                excludedV6 = excludedV6,
                dnsServers = dnsServers,
                appFilter = appFilter,
            )
        } else {
            val claimedV4 = CidrMath
                .subtract(baseV4, excludedV4)
                .filterIsInstance<Cidr.V4>()
            val claimedV6 = CidrMath
                .subtract(baseV6, excludedV6)
                .filterIsInstance<Cidr.V6>()
            RoutePlan(
                claimedV4 = claimedV4,
                claimedV6 = claimedV6,
                excludedV4 = emptyList(),
                excludedV6 = emptyList(),
                dnsServers = dnsServers,
                appFilter = appFilter,
            )
        }
    }

    private suspend fun collectDirectCidrs(
        config: RoutingConfig,
    ): Pair<List<Cidr.V4>, List<Cidr.V6>> {
        val all = mutableListOf<Cidr>()
        for (rule in config.directRoutes) {
            if (rule.enabled) all += rule.cidr
        }
        for (country in config.geoDirectCountries) {
            all += geoIp.cidrs(country)
        }
        return all.filterIsInstance<Cidr.V4>() to all.filterIsInstance<Cidr.V6>()
    }

    private fun resolveDns(config: RoutingConfig): List<String> = when (config.dnsMode) {
        DnsMode.System -> emptyList()
        DnsMode.Cloudflare -> listOf(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
        )
        DnsMode.Google -> listOf(
            "8.8.8.8",
            "8.8.4.4",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
        )
        DnsMode.Custom -> config.customDns
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    companion object {
        val IPV4_DEFAULT: Cidr.V4 = Cidr.parse("0.0.0.0/0") as Cidr.V4
        val IPV6_DEFAULT: Cidr.V6 = Cidr.parse("::/0") as Cidr.V6
    }
}
