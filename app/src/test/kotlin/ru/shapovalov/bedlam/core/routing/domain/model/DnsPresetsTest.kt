package ru.shapovalov.bedlam.core.routing.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `plain presets keep four numeric endpoints on port 53`() {
        assertEquals(
            listOf("1.1.1.1:53", "1.0.0.1:53", "[2606:4700:4700::1111]:53", "[2606:4700:4700::1001]:53"),
            DnsPresets.cloudflare(DnsTransport.Udp),
        )
        assertEquals(
            listOf("8.8.8.8:53", "8.8.4.4:53", "[2001:4860:4860::8888]:53", "[2001:4860:4860::8844]:53"),
            DnsPresets.google(DnsTransport.Tcp),
        )
        assertEquals(DnsPresets.cloudflare(DnsTransport.Udp), DnsPresets.cloudflare(DnsTransport.Tcp))
        assertEquals(DnsPresets.google(DnsTransport.Udp), DnsPresets.google(DnsTransport.Tcp))
    }

    @Test
    fun `http3 presets keep the four numeric urls`() {
        assertEquals(
            listOf(
                "https://1.1.1.1/dns-query",
                "https://1.0.0.1/dns-query",
                "https://[2606:4700:4700::1111]/dns-query",
                "https://[2606:4700:4700::1001]/dns-query",
            ),
            DnsPresets.cloudflare(DnsTransport.Http3),
        )
        assertEquals(
            listOf(
                "https://8.8.8.8/dns-query",
                "https://8.8.4.4/dns-query",
                "https://[2001:4860:4860::8888]/dns-query",
                "https://[2001:4860:4860::8844]/dns-query",
            ),
            DnsPresets.google(DnsTransport.Http3),
        )
    }

    @Test
    fun `dns over tls and https presets name the provider host`() {
        assertEquals(listOf("one.one.one.one:853"), DnsPresets.cloudflare(DnsTransport.Tls))
        assertEquals(listOf("dns.google:853"), DnsPresets.google(DnsTransport.Tls))
        assertEquals(listOf("https://cloudflare-dns.com/dns-query"), DnsPresets.cloudflare(DnsTransport.Https))
        assertEquals(listOf("https://dns.google/dns-query"), DnsPresets.google(DnsTransport.Https))
        assertNotEquals(DnsPresets.cloudflare(DnsTransport.Https), DnsPresets.cloudflare(DnsTransport.Http3))
        assertNotEquals(DnsPresets.google(DnsTransport.Https), DnsPresets.google(DnsTransport.Http3))
        for (transport in listOf(DnsTransport.Tls, DnsTransport.Https)) {
            for (server in DnsPresets.cloudflare(transport) + DnsPresets.google(transport)) {
                assertNull(DnsServer.literalHostOf(server), server)
            }
        }
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
