package ru.shapovalov.bedlam.core.routing.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import ru.shapovalov.hysteria.api.DnsTransport

class DnsServerTest {

    @ParameterizedTest
    @CsvSource(
        "Udp, 1.1.1.1, 1.1.1.1:53",
        "Tcp, 1.1.1.1:5353, 1.1.1.1:5353",
        "Tcp, '  9.9.9.9  ', 9.9.9.9:53",
        "Udp, 2606:4700:4700::1111, [2606:4700:4700::1111]:53",
        "Udp, [2606:4700:4700::1111], [2606:4700:4700::1111]:53",
        "Udp, [2606:4700:4700::1111]:5353, [2606:4700:4700::1111]:5353",
        "Tls, dns.quad9.net, dns.quad9.net:853",
        "Tls, dns.quad9.net:8853, dns.quad9.net:8853",
        "Tls, 9.9.9.9, 9.9.9.9:853",
        "Tls, 2620:fe::fe, [2620:fe::fe]:853",
        "Https, dns.quad9.net, https://dns.quad9.net/dns-query",
        "Https, 9.9.9.9, https://9.9.9.9/dns-query",
        "Https, 2620:fe::fe, https://[2620:fe::fe]/dns-query",
        "Https, [2620:fe::fe]:8443, https://[2620:fe::fe]:8443/dns-query",
        "Https, https://dns.quad9.net/dns-query, https://dns.quad9.net/dns-query",
        "Https, https://dns.quad9.net, https://dns.quad9.net/dns-query",
        "Https, https://dns.quad9.net:8443/custom, https://dns.quad9.net:8443/custom",
        "Https, https://dns.quad9.net/dns-query#frag, https://dns.quad9.net/dns-query",
        "Https, https://[2620:fe::fe]/dns-query, https://[2620:fe::fe]/dns-query",
        "Http3, 1.1.1.1, https://1.1.1.1/dns-query",
        "Http3, https://dns.google/dns-query, https://dns.google/dns-query",
    )
    fun `normalizes valid servers`(transport: DnsTransport, raw: String, expected: String) {
        assertEquals(DnsServerParse.Valid(expected), DnsServer.parse(raw, transport))
    }

    @ParameterizedTest
    @CsvSource(
        "Udp, '', Empty",
        "Udp, '   ', Empty",
        "Udp, dns.quad9.net, HostNotAllowed",
        "Tcp, dns.quad9.net:53, HostNotAllowed",
        "Udp, 1.1.1.1:0, InvalidPort",
        "Udp, 1.1.1.1:65536, InvalidPort",
        "Udp, 1.1.1.1:abc, InvalidPort",
        "Tls, dns.quad9.net:0, InvalidPort",
        "Udp, 1.2.3, InvalidAddress",
        "Udp, 1.2.3.256, InvalidAddress",
        "Udp, [2606:4700::1111, InvalidAddress",
        "Udp, [2606:4700::1111]x, InvalidAddress",
        "Udp, [not-an-ip]:53, InvalidAddress",
        "Udp, :53, InvalidAddress",
        "Tls, -bad.example, InvalidAddress",
        "Tls, bad_host.example, InvalidAddress",
        "Https, http://dns.quad9.net/dns-query, NotHttps",
        "Https, https://user@dns.quad9.net/dns-query, InvalidUrl",
        "Https, https:///dns-query, InvalidUrl",
        "Https, https://dns.quad9.net:99999/, InvalidPort",
        "Https, 1.1.1.1:0, InvalidPort",
        "Https, not a host, InvalidAddress",
    )
    fun `rejects invalid servers`(transport: DnsTransport, raw: String, error: DnsServerError) {
        assertEquals(DnsServerParse.Invalid(error), DnsServer.parse(raw, transport))
    }

    @Test
    fun `normalizeOrNull mirrors parse`() {
        assertEquals("1.1.1.1:53", DnsServer.normalizeOrNull("1.1.1.1", DnsTransport.Udp))
        assertNull(DnsServer.normalizeOrNull("dns.quad9.net", DnsTransport.Udp))
    }
}
