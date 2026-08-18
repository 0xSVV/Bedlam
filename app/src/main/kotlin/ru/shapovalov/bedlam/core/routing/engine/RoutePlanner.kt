package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.DnsServer
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.hysteria.api.DnsTransport
import ru.shapovalov.hysteria.api.DnsUpstream

class RoutePlanner(
    private val supportsExcludeRoute: Boolean,
    private val tunPrefixV4: Cidr.V4,
    private val tunPrefixV6: Cidr.V6,
    private val resolverV4: String,
    private val resolverV6: String,
    private val maxTotalRoutes: Int = DEFAULT_MAX_TOTAL_ROUTES,
    private val systemDnsServers: () -> List<String> = { emptyList() },
) {

    fun plan(config: RoutingConfig, appFilter: AppFilter): RoutePlan {
        val ipv6Enabled = config.ipv6Mode == Ipv6Mode.Enabled
        val baseV4 = listOf(IPV4_DEFAULT)
        // Disabled still claims ::/0 so v6 sinks into the TUN (where the native
        // layer rejects it) instead of leaking around the VPN. Only BypassOnly
        // deliberately lets v6 take the underlying network.
        val baseV6 =
            if (config.ipv6Mode == Ipv6Mode.BypassOnly) emptyList() else listOf(IPV6_DEFAULT)

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

        val transport = DnsPresets.effectiveTransport(config.dnsMode, config.dnsTransport)
        // A resolver on the user's own network is unreachable from the Hysteria
        // server, so it stays a direct resolver: advertised to Android as-is
        // and left to the LAN bypass, exactly as before the tunnel took over.
        val lanDns = lanDnsServers(config, transport)
        val dnsUpstream = resolveDnsUpstream(config, transport, lanDns)

        val dnsServers = listOf(resolverV4) +
                (if (ipv6Enabled) listOf(resolverV6) else emptyList()) +
                lanDns.filter { ipv6Enabled || ':' !in it }

        // The on-TUN resolver sits inside the excluded TUN prefix; a host route
        // beats the exclusion (longest prefix wins) so the OS keeps sending DNS
        // to it. Upstream resolvers are claimed too: an app with a hard-coded
        // resolver must still reach the TUN even when a direct-route source
        // covers that address, or its DNS would leave in plaintext.
        val upstreamRoutes = upstreamHostRoutes(dnsUpstream.servers)
        val resolverRoutesV4 = listOf(Cidr.parseV4("$resolverV4/32")) +
                upstreamRoutes.filterIsInstance<Cidr.V4>()
        val resolverRoutesV6 = if (ipv6Enabled) {
            listOf(Cidr.parseV6("$resolverV6/128")) + upstreamRoutes.filterIsInstance<Cidr.V6>()
        } else {
            emptyList()
        }

        return if (supportsExcludeRoute) {
            RoutePlan(
                claimedV4 = baseV4 + resolverRoutesV4,
                claimedV6 = baseV6 + resolverRoutesV6,
                excludedV4 = excludedV4,
                excludedV6 = excludedV6,
                dnsServers = dnsServers,
                dnsUpstream = dnsUpstream,
                appFilter = appFilter,
                ipv6Enabled = ipv6Enabled,
                mtu = RoutingConfig.resolveMtu(config.mtu),
            )
        } else {
            val claimedV4 = CidrMath
                .coalesce(CidrMath.subtract(baseV4, excludedV4) + resolverRoutesV4)
                .filterIsInstance<Cidr.V4>()
            val claimedV6 = CidrMath
                .coalesce(CidrMath.subtract(baseV6, excludedV6) + resolverRoutesV6)
                .filterIsInstance<Cidr.V6>()
            RoutePlan(
                claimedV4 = claimedV4,
                claimedV6 = claimedV6,
                excludedV4 = emptyList(),
                excludedV6 = emptyList(),
                dnsServers = dnsServers,
                dnsUpstream = dnsUpstream,
                appFilter = appFilter,
                ipv6Enabled = ipv6Enabled,
                mtu = RoutingConfig.resolveMtu(config.mtu),
            )
        }
    }

    private fun resolveDnsUpstream(
        config: RoutingConfig,
        transport: DnsTransport,
        lanDns: List<String>,
    ): DnsUpstream {
        val servers = when (config.dnsMode) {
            DnsMode.System -> systemDnsServers()
                .sortedBy { ':' in it }
                .mapNotNull { DnsServer.normalizeOrNull(it, transport) }

            DnsMode.Cloudflare -> DnsPresets.cloudflare(transport)
            DnsMode.Google -> DnsPresets.google(transport)
            DnsMode.Custom -> config.customDns
                .mapNotNull { DnsServer.normalizeOrNull(it, transport) }
                .filterNot { normalized ->
                    DnsServer.literalHostOf(normalized)?.let { it in lanDns } == true
                }
        }.ifEmpty { DnsPresets.cloudflare(transport) }
        return DnsUpstream(transport, servers)
    }

    private fun lanDnsServers(config: RoutingConfig, transport: DnsTransport): List<String> {
        if (config.dnsMode != DnsMode.Custom) return emptyList()
        return config.customDns
            .mapNotNull { DnsServer.normalizeOrNull(it, transport) }
            .mapNotNull { DnsServer.literalHostOf(it) }
            .filter { host -> Cidr.parseOrNull(hostRoute(host))?.let(::isLanAddress) == true }
            .distinct()
    }

    private fun upstreamHostRoutes(servers: List<String>): List<Cidr> =
        servers
            .mapNotNull { DnsServer.literalHostOf(it) }
            .distinct()
            .mapNotNull { Cidr.parseOrNull(hostRoute(it)) }
            .filterNot(::isLanAddress)

    private fun hostRoute(host: String): String = "$host/${if (':' in host) 128 else 32}"

    private fun isLanAddress(c: Cidr): Boolean = when (c) {
        is Cidr.V4 -> LanRanges.IPV4.any { CidrMath.contains(it, c) }
        is Cidr.V6 -> LanRanges.IPV6.any { CidrMath.contains(it, c) }
    }

    companion object {
        private const val TAG = "RoutePlanner"
        const val DEFAULT_MAX_TOTAL_ROUTES: Int = 8192

        val IPV4_DEFAULT: Cidr.V4 = Cidr.parseV4("0.0.0.0/0")
        val IPV6_DEFAULT: Cidr.V6 = Cidr.parseV6("::/0")
    }
}
