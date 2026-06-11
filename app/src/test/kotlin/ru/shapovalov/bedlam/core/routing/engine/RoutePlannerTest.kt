package ru.shapovalov.bedlam.core.routing.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

class RoutePlannerTest {

    private val tunV4 = Cidr.parse("172.19.0.1/30") as Cidr.V4
    private val tunV6 = Cidr.parse("fdfe:dcba:9876::1/126") as Cidr.V6

    private fun planner(
        supportsExclude: Boolean = true,
        max: Int = RoutePlanner.DEFAULT_MAX_TOTAL_ROUTES,
    ): RoutePlanner = RoutePlanner(supportsExclude, tunV4, tunV6, maxTotalRoutes = max)

    private fun cidrSource(cidr: String, enabled: Boolean = true): ResolvedSource {
        val parsed = Cidr.parse(cidr)
        return ResolvedSource(
            source = DirectRouteSource.Cidr(cidr, parsed, "", enabled, 0),
            cidrs = listOf(parsed),
            lastResolvedMillis = null,
            lastError = null,
        )
    }

    private fun asnSource(
        id: String,
        cidrs: List<String>,
        enabled: Boolean = true
    ): ResolvedSource =
        ResolvedSource(
            source = DirectRouteSource.Asn(id, 13238, "", enabled, 0),
            cidrs = cidrs.map(Cidr::parse),
            lastResolvedMillis = 0L,
            lastError = null,
        )

    @Nested
    inner class Api33AndUp {

        @Test
        fun `bypass-LAN on excludes RFC1918 ranges`() {
            val plan = planner().plan(RoutingConfig(bypassLan = true), AppFilter())
            assertTrue(plan.claimedV4.contains(RoutePlanner.IPV4_DEFAULT))
            assertTrue(plan.claimedV6.contains(RoutePlanner.IPV6_DEFAULT))
            assertTrue(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("192.168.0.0/16") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("172.16.0.0/12") })
        }

        @Test
        fun `loopback is never excluded - VpnService rejects it`() {
            val plan = planner().plan(RoutingConfig(bypassLan = true), AppFilter())
            assertFalse(plan.excludedV4.any { it == Cidr.parse("127.0.0.0/8") })
            assertFalse(plan.excludedV6.any { it == Cidr.parse("::1/128") })
        }

        @Test
        fun `bypass-LAN off only excludes the TUN prefix`() {
            val plan = planner().plan(RoutingConfig(bypassLan = false), AppFilter())
            assertFalse(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
            assertTrue(plan.excludedV4.any { CidrMath.contains(it, tunV4) })
        }

        @Test
        fun `ipv6 disabled still claims v6 default as a sink`() {
            val plan = planner().plan(RoutingConfig(ipv6Mode = Ipv6Mode.Disabled), AppFilter())
            assertEquals(listOf(RoutePlanner.IPV6_DEFAULT), plan.claimedV6)
            assertFalse(plan.ipv6Enabled)
        }

        @Test
        fun `ipv6 bypass-only claims no v6 routes`() {
            val plan = planner().plan(RoutingConfig(ipv6Mode = Ipv6Mode.BypassOnly), AppFilter())
            assertTrue(plan.claimedV6.isEmpty())
            assertFalse(plan.ipv6Enabled)
        }

        @Test
        fun `ipv6 disabled drops v6 DNS servers`() {
            val plan = planner().plan(
                RoutingConfig(ipv6Mode = Ipv6Mode.Disabled, dnsMode = DnsMode.Cloudflare),
                AppFilter(),
            )
            assertTrue(plan.dnsServers.contains("1.1.1.1"))
            assertFalse(plan.dnsServers.any { ':' in it })
        }

        @Test
        fun `dns Cloudflare uses CF addresses`() {
            val plan = planner().plan(RoutingConfig(dnsMode = DnsMode.Cloudflare), AppFilter())
            assertTrue(plan.dnsServers.contains("1.1.1.1"))
            assertTrue(plan.dnsServers.contains("1.0.0.1"))
        }

        @Test
        fun `dns System uses underlying network resolvers`() {
            val p = RoutePlanner(
                supportsExcludeRoute = true,
                tunPrefixV4 = tunV4,
                tunPrefixV6 = tunV6,
                systemDnsServers = { listOf("9.9.9.9") },
            )
            val plan = p.plan(RoutingConfig(dnsMode = DnsMode.System), AppFilter())
            assertEquals(listOf("9.9.9.9"), plan.dnsServers)
        }

        @Test
        fun `dns System falls back to Cloudflare when no public resolvers`() {
            val plan = planner().plan(RoutingConfig(dnsMode = DnsMode.System), AppFilter())
            assertEquals(RoutePlanner.CLOUDFLARE_DNS, plan.dnsServers)
        }

        @Test
        fun `dns Custom strips blanks`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    customDns = listOf("9.9.9.9", "  ", "", "  149.112.112.112  ")
                ),
                AppFilter(),
            )
            assertEquals(listOf("9.9.9.9", "149.112.112.112"), plan.dnsServers)
        }

        @Test
        fun `tunnel DNS servers are claimed as host routes`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Cloudflare,
                    sources = listOf(cidrSource("1.1.1.0/24")),
                ),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("1.1.1.0/24") })
            assertTrue(plan.claimedV4.any { it == Cidr.parse("1.1.1.1/32") })
        }

        @Test
        fun `private custom DNS is not claimed`() {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Custom, customDns = listOf("192.168.1.1")),
                AppFilter(),
            )
            assertFalse(plan.claimedV4.any { it == Cidr.parse("192.168.1.1/32") })
        }

        @Test
        fun `cidr source appears as exclusion`() {
            val plan = planner().plan(
                RoutingConfig(sources = listOf(cidrSource("1.2.3.0/24"))),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("1.2.3.0/24") })
        }

        @Test
        fun `asn source CIDRs propagate to excluded set`() {
            val plan = planner().plan(
                RoutingConfig(
                    sources = listOf(
                        asnSource(
                            "asn1",
                            listOf("5.45.192.0/18", "77.88.0.0/18")
                        )
                    )
                ),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("5.45.192.0/18") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("77.88.0.0/18") })
        }

        @Test
        fun `disabled source is ignored`() {
            val plan = planner().plan(
                RoutingConfig(sources = listOf(cidrSource("1.2.3.0/24", enabled = false))),
                AppFilter(),
            )
            assertFalse(plan.excludedV4.any { it == Cidr.parse("1.2.3.0/24") })
        }

        @Test
        fun `huge source set is dropped, LAN remains`() {
            val flood = (0 until 200).map {
                "11.${(it shr 8) and 0xFF}.${it and 0xFF}.0/24"
            }
            val plan = planner(max = 50).plan(
                RoutingConfig(sources = listOf(asnSource("a", flood))),
                AppFilter(),
            )
            assertFalse(plan.excludedV4.any { it == Cidr.parse("11.0.0.0/24") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
        }

        @Test
        fun `TUN prefix is always excluded`() {
            val plan = planner().plan(
                RoutingConfig(bypassLan = false, ipv6Mode = Ipv6Mode.Enabled),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == tunV4 })
            assertTrue(plan.excludedV6.any { it == tunV6 })
        }

        @Test
        fun `app filter is forwarded as-is`() {
            val filter = AppFilter(
                mode = ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode.Allowlist,
                packages = setOf("com.example.app", "com.example.other"),
            )
            val plan = planner().plan(RoutingConfig(), filter)
            assertEquals(filter, plan.appFilter)
        }
    }

    @Nested
    inner class PreApi33 {

        @Test
        fun `excludedV4 always empty - subtraction is baked into claimed list`() {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.isEmpty())
            assertTrue(plan.excludedV6.isEmpty())
        }

        @Test
        fun `claimedV4 omits LAN ranges when bypass-LAN is on`() {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            val lanProbe = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
            val isLanCovered = plan.claimedV4.any {
                CidrMath.contains(it, Cidr.V4(lanProbe, 32))
            }
            assertFalse(isLanCovered)
            val publicProbe = byteArrayOf(8, 8, 8, 8)
            val isPublicCovered = plan.claimedV4.any {
                CidrMath.contains(it, Cidr.V4(publicProbe, 32))
            }
            assertTrue(isPublicCovered)
        }
    }
}
