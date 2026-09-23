package ru.shapovalov.bedlam.feature.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.shapovalov.bedlam.feature.logs.data.RedactionRules
import ru.shapovalov.bedlam.feature.logs.data.redactAddresses
import ru.shapovalov.bedlam.feature.logs.data.redactionRules
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.hysteria.api.TunConfig

class LogRedactionTest {

    private val rules = redactionRules(emptyList())

    private fun redact(text: String, rules: RedactionRules = this.rules) = redactAddresses(text, rules)

    @Test
    fun `public IPv4 addresses become stable numbered tokens`() {
        assertEquals(
            "Dial <ip-1>:443, then <ip-2> and <ip-1> again",
            redact("Dial 203.0.113.7:443, then 198.51.100.2 and 203.0.113.7 again"),
        )
    }

    @Test
    fun `compressed IPv6 addresses are masked everywhere on the line`() {
        assertEquals(
            "Resolved <ip-1> → <ip-1> (candidates: [<ip-1>])",
            redact("Resolved 2a0c:9a46:1e00:5::a → 2a0c:9a46:1e00:5::a (candidates: [2a0c:9a46:1e00:5::a])"),
        )
    }

    @Test
    fun `a bracketed IPv6 address keeps its port`() {
        assertEquals(
            "Starting client for [<ip-1>]:8443",
            redact("Starting client for [2a0c:9a46:1e00:5::a]:8443"),
        )
    }

    @Test
    fun `one address spelled two ways gets one token`() {
        assertEquals(
            "<ip-1> and <ip-1> and <ip-2>",
            redact("2A0C:9a46:1e00:5::a and 2a0c:9a46:1e00:5:0:0:0:a and 2001:db8::1"),
        )
    }

    @Test
    fun `an IPv6 address followed by a colon is still masked`() {
        assertEquals(
            "dial <ip-1>: timeout, server <ip-2>: unreachable",
            redact("dial 2a0c::a: timeout, server 2001:db8:1::5: unreachable"),
        )
    }

    @Test
    fun `an unbracketed IPv6 address with a port keeps the port`() {
        assertEquals(
            "addr <ip-1>:443 and <ip-2>:8443",
            redact("addr 2a0c:9a46:1e00:5:0:0:0:a:443 and ::ffff:203.0.113.7:8443"),
        )
    }

    @Test
    fun `IPv4 mapped IPv6 addresses follow the embedded address`() {
        assertEquals(
            "<ip-1> from ::ffff:192.168.1.10 and <ip-1>",
            redact("::ffff:203.0.113.7 from ::ffff:192.168.1.10 and 203.0.113.7"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "127.0.0.1:1080",
            "10.0.2.15",
            "172.16.4.2",
            "172.31.255.255",
            "192.168.1.1:53",
            "169.254.10.1",
            "100.64.0.1",
            "0.0.0.0:0",
            "224.0.0.251",
            "255.255.255.255",
            "[::1]:53",
            "[::]:0",
            "fe80::1%wlan0",
            "fd12:3456::1",
            "ff02::fb",
            "listen 172.19.0.1 and 172.19.0.2:53",
            "fdfe:dcba:9876::1 and [fdfe:dcba:9876::2]:53",
        ],
    )
    fun `local, private and tunnel addresses stay`(line: String) {
        assertEquals(line, redact(line))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "tls|1.1.1.1:853",
            "udp|8.8.4.4:53",
            "https://[2606:4700:4700::1111]/dns-query",
            "quic|[2001:4860:4860::8844]:853",
            "https://1.0.0.1/dns-query",
        ],
    )
    fun `public resolver addresses from the presets stay`(line: String) {
        assertEquals(line, redact(line))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Bedlam 1.6.5 (10605) · Hysteria core v2.12.3",
            "15:41:40.506 INFO tunnel connected",
            "Exported 2026-09-24 15:41:40 +03:00",
            "at 15:41:40 after 1:30:00",
            "oid 1.2.3.4.5 and 999.1.1.1 and 1.2.3.256",
            "mac aa:bb:cc:dd:ee:ff",
            "Foo::bar and std::string",
            "build 20260924.1.2.3x",
        ],
    )
    fun `versions, times and other dotted or colon text stay`(line: String) {
        assertEquals(line, redact(line))
    }

    @Test
    fun `saved server hosts become numbered host tokens`() {
        val rules = RedactionRules(
            keptAddresses = listOf(TunConfig.IPV4_DNS_ADDRESS),
            hostNames = setOf("vpn.example.com", "realm.example.org"),
        )

        assertEquals(
            "Dial <host-1>:443 (<ip-1>), rendezvous <host-2>, again <host-1>",
            redact("Dial VPN.example.com:443 (203.0.113.7), rendezvous realm.example.org, again vpn.example.com", rules),
        )
    }

    @Test
    fun `only whole host names are masked`() {
        val rules = RedactionRules(hostNames = setOf("vpn.example.com"))
        val line = "cdn.vpn.example.com, my-vpn.example.com, vpn.example.com.evil, vpn.example.community"

        assertEquals(line, redact(line, rules))
        assertEquals("host <host-1>.", redact("host vpn.example.com.", rules))
    }

    @Test
    fun `saved profiles supply their server and realm hosts`() {
        val profiles = listOf(
            testProfile("a", address = "vpn.example.com:443"),
            testProfile("b", address = "Hop.Example.net:20000-30000,40000"),
            testProfile("c", address = "[2001:db8::1]:443"),
            testProfile("d", address = "203.0.113.7:443"),
            testProfile("e", address = "realm://token@realm.example.org/my-realm"),
            testProfile("f", address = "realm+http://rv.example.io:8080/r"),
            testProfile("g", address = "bare.example.com"),
        )

        assertEquals(
            setOf("vpn.example.com", "hop.example.net", "realm.example.org", "rv.example.io", "bare.example.com"),
            redactionRules(profiles).hostNames,
        )
    }

    @Test
    fun `single label server hosts are not masked`() {
        val profiles = listOf(
            testProfile("a", address = "vpn:443"),
            testProfile("b", address = "nas"),
        )

        assertEquals(emptySet<String>(), redactionRules(profiles).hostNames)
    }

    @Test
    fun `the tunnel and preset resolver addresses are kept by default`() {
        val kept = redactionRules(emptyList()).keptAddresses

        listOf(
            TunConfig.IPV4_ADDRESS,
            TunConfig.IPV6_ADDRESS,
            TunConfig.IPV4_DNS_ADDRESS,
            TunConfig.IPV6_DNS_ADDRESS,
            "1.1.1.1",
            "2606:4700:4700::1001",
            "8.8.8.8",
            "2001:4860:4860::8888",
        ).forEach { assertTrue(it in kept, "$it is not kept") }
    }
}
