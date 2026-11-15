package ru.shapovalov.bedlam.core.routing.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.routing.domain.model.*
import ru.shapovalov.hysteria.api.DnsTransport

class NewDnsPresetsTest {
    @Test fun `OpenDNS supports TLS and keeps separate TLS and HTTPS bootstrap addresses`() {
        val mode = DnsMode.valueOf("OpenDns")
        assertEquals(listOf(DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Tls, DnsTransport.Https), DnsPresets.supportedTransports(mode))
        assertEquals(DnsTransport.Tls, DnsPresets.effectiveTransport(mode, DnsTransport.Tls))
        val plain = DirectDnsPlanner.plan(RoutingConfig(directDnsMode = mode), emptyList())
        assertTrue("208.67.222.222:53" in plain.servers)
        val secure = DirectDnsPlanner.plan(RoutingConfig(directDnsMode = mode, directDnsTransport = DnsTransport.Https), emptyList())
        assertEquals(listOf("https://doh.opendns.com/dns-query"), secure.servers)
        assertTrue("146.112.41.2" in secure.bootstrap.getValue("doh.opendns.com"))
        val tls = DirectDnsPlanner.plan(RoutingConfig(directDnsMode = mode, directDnsTransport = DnsTransport.Tls), emptyList())
        assertEquals(DnsTransport.Tls, tls.transport)
        assertEquals(listOf("dns.opendns.com:853"), tls.servers)
        assertEquals(
            mapOf("dns.opendns.com" to listOf("208.67.222.222", "208.67.220.220", "2620:119:35::35", "2620:119:53::53")),
            tls.bootstrap,
        )
    }

    @Test fun `AdGuard uses filtering service with bootstrap for both encrypted transports`() {
        val mode = DnsMode.valueOf("AdGuard")
        assertEquals(listOf(DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Tls, DnsTransport.Https), DnsPresets.supportedTransports(mode))
        for (transport in DnsPresets.supportedTransports(mode)) {
            val plan = DirectDnsPlanner.plan(RoutingConfig(directDnsMode = mode, directDnsTransport = transport), emptyList())
            assertTrue(plan.servers.all { DnsServer.normalizeOrNull(it, transport) == it })
            if (transport in listOf(DnsTransport.Tls, DnsTransport.Https)) {
                assertEquals(listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"), plan.bootstrap.getValue("dns.adguard-dns.com"))
            } else assertTrue("94.140.14.14:53" in plan.servers)
        }
    }
}
