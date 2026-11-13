package ru.shapovalov.bedlam.feature.routing.presentation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.hysteria.api.DnsTransport

class DirectDnsInputTest {
    @Test
    fun `custom encrypted hostname matching ignores case without changing URL path`() {
        for (transport in listOf(DnsTransport.Tls, DnsTransport.Https)) {
            val endpoint = if (transport == DnsTransport.Tls) "DNS.Example:853"
                else "https://DNS.Example/DNS-query?Token=AbC"
            DirectDnsInput.parse(
                DnsMode.Custom, transport, endpoint, "Dns.Example=192.0.2.53",
            ).apply {
                assertEquals(listOf(endpoint), servers)
                assertEquals(mapOf("dns.example" to listOf("192.0.2.53")), bootstrap)
            }
        }
    }

    @Test
    fun `plain DNS ignores hidden malformed bootstrap drafts`() {
        for (transport in listOf(DnsTransport.Udp, DnsTransport.Tcp)) {
            DirectDnsInput.parse(
                DnsMode.Custom, transport, "9.9.9.9", "unfinished bootstrap",
            ).apply {
                assertEquals(listOf("9.9.9.9:53"), servers)
                assertTrue(bootstrap.isEmpty())
            }
        }
    }

    @Test
    fun `encrypted custom endpoint preserves hostname and validates bootstrap addresses`() {
        DirectDnsInput.parse(
            DnsMode.Custom, DnsTransport.Https,
            "https://dns.example/dns-query",
            "dns.example=192.0.2.1, 2001:db8::53",
        ).apply {
            assertEquals(listOf("https://dns.example/dns-query"), servers)
            assertEquals(mapOf("dns.example" to listOf("192.0.2.1", "2001:db8::53")), bootstrap)
        }
    }

    @Test
    fun `incomplete or malformed encrypted configuration is never accepted`() {
        listOf("", "dns.example=other.example", "dns.example=", "dns.example=192.0.2.1\ndns.example=192.0.2.2").forEach { bootstrap ->
            assertThrows(IllegalArgumentException::class.java) {
                DirectDnsInput.parse(DnsMode.Custom, DnsTransport.Tls, "dns.example", bootstrap)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectDnsInput.parse(DnsMode.Custom, DnsTransport.Tcp, "", "")
        }
    }

    @Test
    fun `preset supplies bootstrap and system does not require editable endpoints`() {
        assertTrue(DirectDnsInput.parse(DnsMode.Cloudflare, DnsTransport.Https, "", "").bootstrap.isNotEmpty())
        assertTrue(DirectDnsInput.parse(DnsMode.System, DnsTransport.Tcp, "", "").servers.isEmpty())
    }
}
