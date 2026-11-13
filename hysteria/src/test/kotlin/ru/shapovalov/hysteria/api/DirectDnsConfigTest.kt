package ru.shapovalov.hysteria.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DirectDnsConfigTest {
    @Test
    fun `encrypted direct DNS wire config preserves hostname and explicit bootstrap IPs`() {
        val config = DirectDnsConfig(
            servers = listOf("https://dns.test/dns-query"),
            domains = listOf(DirectDnsDomain("rule-id", "direct.example")),
            transport = DnsTransport.Https,
            bootstrap = mapOf("dns.test" to listOf("192.0.2.1", "2001:db8::1")),
        )
        val wire = Json.parseToJsonElement(config.toWireJson()).jsonObject
        assertEquals("https", wire.getValue("transport").jsonPrimitive.content)
        assertEquals("https://dns.test/dns-query", wire.getValue("servers").jsonArray.single().jsonPrimitive.content)
        assertEquals(
            listOf("192.0.2.1", "2001:db8::1"),
            wire.getValue("bootstrap").jsonObject.getValue("dns.test").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("rule-id", wire.getValue("domains").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `legacy direct DNS remains system UDP`() {
        val config = DirectDnsConfig(servers = listOf("192.0.2.53:53"))
        assertEquals(DnsTransport.Udp, config.transport)
        assertEquals(emptyMap<String, List<String>>(), config.bootstrap)
        val wire = Json.parseToJsonElement(config.toWireJson()).jsonObject
        assertEquals("udp", wire.getValue("transport").jsonPrimitive.content)
        assertEquals("192.0.2.53:53", wire.getValue("servers").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `direct DNS rejects transports reserved for remote DNS`() {
        for (transport in listOf(DnsTransport.Doq, DnsTransport.Http3)) {
            assertThrows(IllegalArgumentException::class.java) {
                DirectDnsConfig(servers = listOf("192.0.2.1"), transport = transport)
            }
        }
    }

    @Test
    fun `bootstrap addresses must be literal IPs`() {
        for (addresses in listOf(emptyList(), listOf("dns.other"), listOf("192.0.2.1:853"))) {
            assertThrows(IllegalArgumentException::class.java) {
                DirectDnsConfig(
                    servers = listOf("dns.test:853"),
                    transport = DnsTransport.Tls,
                    bootstrap = mapOf("dns.test" to addresses),
                )
            }
        }
    }
}
