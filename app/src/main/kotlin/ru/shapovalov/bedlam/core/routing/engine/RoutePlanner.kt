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
 * VpnService.Builder serializes its config over a Binder transaction with a ~1 MB
 * cap; large GeoIP selections (CN, RU, US) can easily produce 10k+ CIDRs and blow
 * past it. [maxTotalRoutes] is a soft budget — if the full plan would exceed it,
 * GeoIP exclusions are dropped first, keeping LAN bypass and user-defined direct
 * routes intact.
 *
 * @property supportsExcludeRoute on API 33+, ships exclusions verbatim; otherwise
 *   subtracts on our side.
 * @property tunPrefixV4 the local TUN v4 prefix; always excluded to avoid loops.
 * @property tunPrefixV6 the local TUN v6 prefix.
 * @property maxTotalRoutes claimed+excluded ceiling before GeoIP is dropped.
 */
class RoutePlanner(
    private val supportsExcludeRoute: Boolean,
    private val tunPrefixV4: Cidr.V4,
    private val tunPrefixV6: Cidr.V6,
    private val geoIp: GeoIpDatabase,
    private val maxTotalRoutes: Int = DEFAULT_MAX_TOTAL_ROUTES,
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

        val directV4 = config.directRoutes.asSequence()
            .filter { it.enabled }
            .map { it.cidr }
            .filterIsInstance<Cidr.V4>()
            .toList()
        val directV6 = config.directRoutes.asSequence()
            .filter { it.enabled }
            .map { it.cidr }
            .filterIsInstance<Cidr.V6>()
            .toList()

        val coreV4 = CidrMath
            .coalesce(systemExclusionsV4 + lanExclusionsV4 + directV4)
            .filterIsInstance<Cidr.V4>()
        val coreV6 = CidrMath
            .coalesce(systemExclusionsV6 + lanExclusionsV6 + directV6)
            .filterIsInstance<Cidr.V6>()

        val rawGeoCount = countGeoCidrs(config)
        val budgetForGeo = maxTotalRoutes - baseV4.size - baseV6.size - coreV4.size - coreV6.size

        val (excludedV4, excludedV6) = if (rawGeoCount == 0 || rawGeoCount > budgetForGeo) {
            if (rawGeoCount > budgetForGeo) {
                android.util.Log.w(
                    TAG,
                    "GeoIP bypass dropped: $rawGeoCount raw CIDRs exceed budget $budgetForGeo. " +
                        "Reduce selected countries."
                )
            }
            coreV4 to coreV6
        } else {
            val (geoV4, geoV6) = collectGeoCidrs(config)
            val fullV4 = CidrMath.coalesce(coreV4 + geoV4).filterIsInstance<Cidr.V4>()
            val fullV6 = CidrMath.coalesce(coreV6 + geoV6).filterIsInstance<Cidr.V6>()
            fullV4 to fullV6
        }

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

    private suspend fun countGeoCidrs(config: RoutingConfig): Int {
        if (config.geoDirectCountries.isEmpty()) return 0
        var total = 0
        for (country in config.geoDirectCountries) total += geoIp.cidrs(country).size
        return total
    }

    private suspend fun collectGeoCidrs(
        config: RoutingConfig,
    ): Pair<List<Cidr.V4>, List<Cidr.V6>> {
        if (config.geoDirectCountries.isEmpty()) return emptyList<Cidr.V4>() to emptyList()
        val all = mutableListOf<Cidr>()
        for (country in config.geoDirectCountries) all += geoIp.cidrs(country)
        val coalesced = CidrMath.coalesce(all)
        return coalesced.filterIsInstance<Cidr.V4>() to coalesced.filterIsInstance<Cidr.V6>()
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
        /** Android's `VpnService.Builder` serializes routes over a ~1 MB Binder
         *  transaction; ~10k `RouteInfo` entries fit in that envelope, so 8192
         *  is a safe ceiling with headroom for other parcel data. */
        const val DEFAULT_MAX_TOTAL_ROUTES: Int = 8192

        val IPV4_DEFAULT: Cidr.V4 = Cidr.parse("0.0.0.0/0") as Cidr.V4
        val IPV6_DEFAULT: Cidr.V6 = Cidr.parse("::/0") as Cidr.V6
    }
}
