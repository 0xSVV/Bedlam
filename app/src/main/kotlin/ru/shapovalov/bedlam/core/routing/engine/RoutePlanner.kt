package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

class RoutePlanner(
    private val supportsExcludeRoute: Boolean,
    private val tunPrefixV4: Cidr.V4,
    private val tunPrefixV6: Cidr.V6,
    private val maxTotalRoutes: Int = DEFAULT_MAX_TOTAL_ROUTES,
) {

    fun plan(config: RoutingConfig, appFilter: AppFilter): RoutePlan {
        val baseV4 = listOf(IPV4_DEFAULT)
        val baseV6 = if (config.ipv6Mode == Ipv6Mode.Enabled) listOf(IPV6_DEFAULT) else emptyList()

        val systemExclusionsV4 = listOf<Cidr.V4>(tunPrefixV4)
        val systemExclusionsV6 = listOf<Cidr.V6>(tunPrefixV6)

        val lanExclusionsV4 = if (config.bypassLan) LanRanges.IPV4 else emptyList()
        val lanExclusionsV6 = if (config.bypassLan) LanRanges.IPV6 else emptyList()

        val budget = maxTotalRoutes - baseV4.size - baseV6.size -
            systemExclusionsV4.size - systemExclusionsV6.size -
            lanExclusionsV4.size - lanExclusionsV6.size

        val enabledSources = config.sources
            .filter { it.source.enabled && it.cidrs.isNotEmpty() }
            .sortedBy { it.cidrs.size }
        val kept = mutableListOf<Cidr>()
        var used = 0
        for (resolved in enabledSources) {
            if (used + resolved.cidrs.size > budget) {
                android.util.Log.w(
                    TAG,
                    "Source dropped (over budget): ${resolved.source.label()} " +
                        "(${resolved.cidrs.size} CIDRs, budget remaining ${budget - used})"
                )
                continue
            }
            kept += resolved.cidrs
            used += resolved.cidrs.size
        }
        val keptSourceV4 = kept.filterIsInstance<Cidr.V4>()
        val keptSourceV6 = kept.filterIsInstance<Cidr.V6>()

        val excludedV4Raw: List<Cidr> = systemExclusionsV4 + lanExclusionsV4 + keptSourceV4
        val excludedV6Raw: List<Cidr> = systemExclusionsV6 + lanExclusionsV6 + keptSourceV6
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
        private const val TAG = "RoutePlanner"
        const val DEFAULT_MAX_TOTAL_ROUTES: Int = 8192

        val IPV4_DEFAULT: Cidr.V4 = Cidr.parse("0.0.0.0/0") as Cidr.V4
        val IPV6_DEFAULT: Cidr.V6 = Cidr.parse("::/0") as Cidr.V6
    }
}
