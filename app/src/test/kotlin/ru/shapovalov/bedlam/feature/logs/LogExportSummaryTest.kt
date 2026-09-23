package ru.shapovalov.bedlam.feature.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.feature.logs.data.connectionSummary
import ru.shapovalov.bedlam.feature.logs.data.routingSummary
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.DnsTransport
import java.time.LocalDateTime
import java.time.ZoneOffset

class LogExportSummaryTest {

    private fun asn(asn: Int, enabled: Boolean) = ResolvedSource(
        source = DirectRouteSource.Asn("as$asn", asn, "", enabled, 0),
        cidrs = emptyList(),
        lastResolvedMillis = null,
        lastError = null,
    )

    @Test
    fun `the default routing reads as one line`() {
        assertEquals(
            "DNS Cloudflare over TCP · IPv6 on · MTU auto (1280) · all apps · LAN bypass on · 0 direct-route sources",
            routingSummary(RoutingConfig(), AppFilter()),
        )
    }

    @Test
    fun `the summary names the effective DNS transport and custom servers count`() {
        val routing = RoutingConfig(
            dnsMode = DnsMode.Custom,
            dnsTransport = DnsTransport.Doq,
            customDns = listOf("dns.example:853", "9.9.9.9:853"),
            ipv6Mode = Ipv6Mode.BypassOnly,
            mtu = 1400,
            bypassLan = false,
            sources = listOf(asn(1, true), asn(2, false), asn(3, true)),
        )
        val apps = AppFilter(AppFilterMode.Allowlist, setOf("a", "b", "c"))

        assertEquals(
            "DNS Custom (2 servers) over QUIC · IPv6 bypass only · MTU 1400 · allowlist of 3 apps · LAN bypass off · 2 direct-route sources",
            routingSummary(routing, apps),
        )
    }

    @Test
    fun `an unsupported preset transport reports the one in use`() {
        val routing = RoutingConfig(
            dnsMode = DnsMode.Google,
            dnsTransport = DnsTransport.Doq,
            ipv6Mode = Ipv6Mode.Disabled,
        )
        val apps = AppFilter(AppFilterMode.Blocklist, setOf("a"))

        assertEquals(
            "DNS Google over TLS · IPv6 off · MTU auto (1280) · blocklist of 1 app · LAN bypass on · 0 direct-route sources",
            routingSummary(routing, apps),
        )
    }

    @Test
    fun `each connection state reads plainly`() {
        val zone = ZoneOffset.ofHours(3)
        val since = LocalDateTime.of(2026, 9, 24, 15, 0, 1).toInstant(zone).toEpochMilli()

        assertEquals(
            "Connected since 2026-09-24 15:00:01, UDP relay on",
            connectionSummary(testConnected(since), zone),
        )
        assertEquals("Connecting", connectionSummary(ConnectionState.Connecting, zone))
        assertEquals(
            "Reconnecting, attempt 3: network changed",
            connectionSummary(ConnectionState.Reconnecting(3, "network changed"), zone),
        )
        assertEquals(
            "Disconnected (USER)",
            connectionSummary(ConnectionState.Disconnected(DisconnectReason.USER), zone),
        )
        assertEquals("Error: handshake timeout", connectionSummary(ConnectionState.Error("handshake timeout"), zone))
    }
}
