package ru.shapovalov.bedlam.core.routing.engine

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteRule
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

class RoutePlannerTest {

    private val tunV4 = Cidr.parse("172.19.0.1/30") as Cidr.V4
    private val tunV6 = Cidr.parse("fdfe:dcba:9876::1/126") as Cidr.V6

    private class FakeGeoIp(private val byCountry: Map<CountryCode, List<Cidr>> = emptyMap()) :
        GeoIpDatabase {
        override suspend fun availableCountries(): List<CountryCode> = byCountry.keys.toList()
        override suspend fun cidrs(country: CountryCode): List<Cidr> = byCountry[country].orEmpty()
        override suspend fun isLoaded(): Boolean = byCountry.isNotEmpty()
    }

    private fun planner(
        supportsExclude: Boolean = true,
        geo: GeoIpDatabase = FakeGeoIp(),
    ): RoutePlanner = RoutePlanner(supportsExclude, tunV4, tunV6, geo)

    @Nested
    inner class Api33AndUp {

        @Test
        fun `bypass-LAN on excludes RFC1918 ranges`() = runTest {
            val plan = planner().plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            assertEquals(listOf(RoutePlanner.IPV4_DEFAULT), plan.claimedV4)
            assertEquals(listOf(RoutePlanner.IPV6_DEFAULT), plan.claimedV6)
            assertTrue(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("192.168.0.0/16") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("172.16.0.0/12") })
        }

        @Test
        fun `loopback is never excluded - VpnService rejects it`() = runTest {
            val plan = planner().plan(RoutingConfig(bypassLan = true), AppFilter())
            assertFalse(plan.excludedV4.any { it == Cidr.parse("127.0.0.0/8") })
            assertFalse(plan.excludedV6.any { it == Cidr.parse("::1/128") })
        }

        @Test
        fun `large GeoIP selection drops geo exclusions but keeps LAN`() = runTest {
            val ru = CountryCode.of("RU")
            // Synthesize a country with > maxTotalRoutes CIDRs (each a unique /32).
            val flood = (0 until 5000).map {
                Cidr.parse("11.${(it shr 16) and 0xFF}.${(it shr 8) and 0xFF}.${it and 0xFF}/32")
            }
            val p = RoutePlanner(
                supportsExcludeRoute = true,
                tunPrefixV4 = tunV4,
                tunPrefixV6 = tunV6,
                geoIp = FakeGeoIp(mapOf(ru to flood)),
                maxTotalRoutes = 100,
            )
            val plan = p.plan(
                RoutingConfig(bypassLan = true, geoDirectCountries = setOf(ru)),
                AppFilter(),
            )
            // Flooded geo CIDRs must be gone; LAN must remain.
            assertFalse(plan.excludedV4.any { it == Cidr.parse("11.0.0.0/32") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
        }

        @Test
        fun `bypass-LAN off only excludes the TUN prefix`() = runTest {
            val plan = planner().plan(
                RoutingConfig(bypassLan = false),
                AppFilter(),
            )
            assertFalse(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
            assertTrue(plan.excludedV4.any { CidrMath.contains(it, tunV4) })
        }

        @Test
        fun `ipv6 disabled means no v6 claimed`() = runTest {
            val plan = planner().plan(
                RoutingConfig(ipv6Mode = Ipv6Mode.Disabled),
                AppFilter(),
            )
            assertTrue(plan.claimedV6.isEmpty())
        }

        @Test
        fun `ipv6 bypass-only means no v6 claimed`() = runTest {
            val plan = planner().plan(
                RoutingConfig(ipv6Mode = Ipv6Mode.BypassOnly),
                AppFilter(),
            )
            assertTrue(plan.claimedV6.isEmpty())
        }

        @Test
        fun `dns Cloudflare uses CF addresses`() = runTest {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Cloudflare),
                AppFilter(),
            )
            assertTrue(plan.dnsServers.contains("1.1.1.1"))
            assertTrue(plan.dnsServers.contains("1.0.0.1"))
        }

        @Test
        fun `dns Google uses 8888 group`() = runTest {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Google),
                AppFilter(),
            )
            assertTrue(plan.dnsServers.contains("8.8.8.8"))
            assertTrue(plan.dnsServers.contains("8.8.4.4"))
        }

        @Test
        fun `dns System produces empty list - caller intentionally allows leaks`() = runTest {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.System),
                AppFilter(),
            )
            assertTrue(plan.dnsServers.isEmpty())
        }

        @Test
        fun `dns Custom strips blanks`() = runTest {
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
        fun `direct routes appear as exclusions`() = runTest {
            val rule = DirectRouteRule(
                id = "r1",
                cidr = Cidr.parse("1.2.3.0/24"),
                comment = "Work",
            )
            val plan = planner().plan(
                RoutingConfig(directRoutes = listOf(rule)),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("1.2.3.0/24") })
        }

        @Test
        fun `disabled direct routes are ignored`() = runTest {
            val rule = DirectRouteRule(
                id = "r1",
                cidr = Cidr.parse("1.2.3.0/24"),
                comment = "Work",
                enabled = false,
            )
            val plan = planner().plan(
                RoutingConfig(directRoutes = listOf(rule)),
                AppFilter(),
            )
            assertFalse(plan.excludedV4.any { it == Cidr.parse("1.2.3.0/24") })
        }

        @Test
        fun `geo bypass pulls CIDRs from the database`() = runTest {
            val ru = CountryCode.of("RU")
            val cidrs: List<Cidr> = listOf(Cidr.parse("5.0.0.0/8"), Cidr.parse("31.0.0.0/16"))
            val p = planner(geo = FakeGeoIp(mapOf(ru to cidrs)))
            val plan = p.plan(
                RoutingConfig(geoDirectCountries = setOf(ru)),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { CidrMath.contains(it, Cidr.parse("5.0.0.0/8")) })
            assertTrue(plan.excludedV4.any { CidrMath.contains(it, Cidr.parse("31.0.0.0/16")) })
        }

        @Test
        fun `TUN prefix is always excluded`() = runTest {
            val plan = planner().plan(
                RoutingConfig(bypassLan = false, ipv6Mode = Ipv6Mode.Enabled),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == tunV4 })
            assertTrue(plan.excludedV6.any { it == tunV6 })
        }

        @Test
        fun `app filter is forwarded as-is`() = runTest {
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
        fun `excludedV4 always empty - subtraction is baked into claimed list`() = runTest {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.isEmpty())
            assertTrue(plan.excludedV6.isEmpty())
        }

        @Test
        fun `claimedV4 omits LAN ranges when bypass-LAN is on`() = runTest {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            // No private range should be covered by any claimed route.
            val lanProbe = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
            val isLanCovered = plan.claimedV4.any {
                CidrMath.contains(it, Cidr.V4(lanProbe, 32))
            }
            assertFalse(isLanCovered)
            // But a public address must still be covered.
            val publicProbe = byteArrayOf(8, 8, 8, 8)
            val isPublicCovered = plan.claimedV4.any {
                CidrMath.contains(it, Cidr.V4(publicProbe, 32))
            }
            assertTrue(isPublicCovered)
        }

        @Test
        fun `claimedV6 is empty when ipv6 is disabled`() = runTest {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(ipv6Mode = Ipv6Mode.Disabled),
                AppFilter(),
            )
            assertTrue(plan.claimedV6.isEmpty())
        }
    }
}
