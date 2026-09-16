package ru.shapovalov.bedlam.core.routing.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.hysteria.api.DnsTransport

class DnsPresetsTest {

    @Test
    fun `every preset entry re-parses to itself under its transport`() {
        for (transport in DnsTransport.entries) {
            for (server in DnsPresets.cloudflare(transport) + DnsPresets.google(transport)) {
                assertEquals(
                    DnsServerParse.Valid(server),
                    DnsServer.parse(server, transport),
                    "$transport $server",
                )
            }
        }
    }

    @Test
    fun `presets pick the port and scheme of the transport`() {
        assertEquals(
            listOf("1.1.1.1:53", "1.0.0.1:53", "[2606:4700:4700::1111]:53", "[2606:4700:4700::1001]:53"),
            DnsPresets.cloudflare(DnsTransport.Udp),
        )
        assertEquals(DnsPresets.cloudflare(DnsTransport.Udp), DnsPresets.cloudflare(DnsTransport.Tcp))
        assertEquals(DnsPresets.cloudflare(DnsTransport.Https), DnsPresets.cloudflare(DnsTransport.Http3))
        assertEquals(
            listOf("8.8.8.8:853", "8.8.4.4:853", "[2001:4860:4860::8888]:853", "[2001:4860:4860::8844]:853"),
            DnsPresets.google(DnsTransport.Tls),
        )
        assertEquals(
            listOf(
                "https://1.1.1.1/dns-query",
                "https://1.0.0.1/dns-query",
                "https://[2606:4700:4700::1111]/dns-query",
                "https://[2606:4700:4700::1001]/dns-query",
            ),
            DnsPresets.cloudflare(DnsTransport.Https),
        )
    }

    @Test
    fun `provider addresses back every numeric preset`() {
        val cloudflare = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        val google = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        assertEquals(cloudflare, DnsPresets.cloudflareAddresses())
        assertEquals(google, DnsPresets.googleAddresses())
        for (transport in listOf(DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Http3)) {
            assertEquals(
                cloudflare,
                DnsPresets.cloudflare(transport).map { DnsServer.literalHostOf(it) },
                "$transport",
            )
            assertEquals(
                google,
                DnsPresets.google(transport).map { DnsServer.literalHostOf(it) },
                "$transport",
            )
        }
    }

    @Test
    fun `system resolvers only speak plain dns`() {
        assertEquals(listOf(DnsTransport.Udp, DnsTransport.Tcp), DnsPresets.supportedTransports(DnsMode.System))
        assertEquals(DnsTransport.entries.toList(), DnsPresets.supportedTransports(DnsMode.Custom))
        assertEquals(DnsTransport.Tcp, DnsPresets.effectiveTransport(DnsMode.System, DnsTransport.Https))
        assertEquals(DnsTransport.Udp, DnsPresets.effectiveTransport(DnsMode.System, DnsTransport.Udp))
        assertEquals(DnsTransport.Https, DnsPresets.effectiveTransport(DnsMode.Google, DnsTransport.Https))
    }

    @Test
    fun `dns over quic is a custom-mode choice only`() {
        for (mode in listOf(DnsMode.Cloudflare, DnsMode.Google)) {
            assertTrue(DnsTransport.Doq !in DnsPresets.supportedTransports(mode), "$mode")
            assertEquals(DnsTransport.Tls, DnsPresets.effectiveTransport(mode, DnsTransport.Doq), "$mode")
        }
        assertTrue(DnsTransport.Doq in DnsPresets.supportedTransports(DnsMode.Custom))
        assertEquals(DnsTransport.Doq, DnsPresets.effectiveTransport(DnsMode.Custom, DnsTransport.Doq))
        assertEquals(DnsTransport.Tcp, DnsPresets.effectiveTransport(DnsMode.System, DnsTransport.Doq))
    }
}
