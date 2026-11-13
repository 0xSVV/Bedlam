package ru.shapovalov.bedlam.core.routing.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.hysteria.api.DnsTransport

class DirectDnsPlanTest {
    private fun planner(excludes: Boolean = true) = RoutePlanner(
        supportsExcludeRoute = excludes,
        tunPrefixV4 = Cidr.parseV4("172.19.0.1/30"),
        tunPrefixV6 = Cidr.parseV6("fdfe:dcba:9876::1/126"),
        resolverV4 = "172.19.0.2",
        resolverV6 = "fdfe:dcba:9876::2",
    ) { listOf("9.9.9.9") }

    @Test
    fun `system direct DNS uses selected network including LAN and transport`() {
        planner().plan(
            RoutingConfig(directDnsTransport = DnsTransport.Tcp),
            AppFilter(),
            directSystemDnsServers = listOf("192.168.1.1", "2001:db8::53"),
        ).apply {
            assertEquals(DnsTransport.Tcp, directDns.transport)
            assertEquals(listOf("192.168.1.1:53", "[2001:db8::53]:53"), directDns.servers)
            assertTrue(directDns.bootstrap.isEmpty())
        }
    }

    @Test
    fun `encrypted direct preset keeps TLS identity and numeric bootstrap on both Android route paths`() {
        for (excludes in listOf(true, false)) {
            planner(excludes).plan(
                RoutingConfig(directDnsMode = DnsMode.Cloudflare, directDnsTransport = DnsTransport.Https),
                AppFilter(),
            ).apply {
                assertEquals(DnsTransport.Https, directDns.transport)
                assertEquals(listOf("https://cloudflare-dns.com/dns-query"), directDns.servers)
                assertTrue("1.1.1.1" in directDns.bootstrap.getValue("cloudflare-dns.com"))
                assertEquals(listOf("172.19.0.2", "fdfe:dcba:9876::2"), dnsServers)
            }
        }
    }

    @Test
    fun `direct provider change changes route plan while keeping remote DNS independent`() {
        val config = RoutingConfig(directDnsMode = DnsMode.Google, directDnsTransport = DnsTransport.Tls)
        val before = planner().plan(config, AppFilter())
        val after = planner().plan(config.copy(directDnsTransport = DnsTransport.Https), AppFilter())
        assertNotEquals(before, after)
        assertEquals(before.dnsUpstream, after.dnsUpstream)
        assertEquals(listOf("dns.google:853"), before.directDns.servers)
    }

    @Test
    fun `invalid custom direct DNS fails without changing provider or encryption`() {
        assertThrows(IllegalArgumentException::class.java) {
            planner().plan(
                RoutingConfig(
                    directDnsMode = DnsMode.Custom,
                    directDnsTransport = DnsTransport.Https,
                    directCustomDns = listOf("http://dns.test/dns-query"),
                ),
                AppFilter(),
            )
        }
    }

    @Test
    fun `persisted bootstrap keys are matched case insensitively`() {
        planner().plan(
            RoutingConfig(
                directDnsMode = DnsMode.Custom,
                directDnsTransport = DnsTransport.Tls,
                directCustomDns = listOf("DNS.Example:853"),
                directDnsBootstrap = mapOf("Dns.Example" to listOf("192.0.2.53")),
            ),
            AppFilter(),
        ).apply {
            assertEquals(mapOf("dns.example" to listOf("192.0.2.53")), directDns.bootstrap)
        }
    }

    @Test
    fun `custom encrypted DNS requires explicit bootstrap for hostname`() {
        val config = RoutingConfig(
            directDnsMode = DnsMode.Custom,
            directDnsTransport = DnsTransport.Tls,
            directCustomDns = listOf("dns.test:853"),
        )
        assertThrows(IllegalArgumentException::class.java) { planner().plan(config, AppFilter()) }
        planner().plan(
            config.copy(directDnsBootstrap = mapOf("dns.test" to listOf("192.0.2.53"))),
            AppFilter(),
        ).apply {
            assertEquals(listOf("dns.test:853"), directDns.servers)
            assertEquals(mapOf("dns.test" to listOf("192.0.2.53")), directDns.bootstrap)
        }
    }
}
