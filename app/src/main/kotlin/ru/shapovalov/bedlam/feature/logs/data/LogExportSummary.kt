package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DnsTransport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun routingSummary(routing: RoutingConfig, appFilter: AppFilter): String {
    val transport = DnsPresets.effectiveTransport(routing.dnsMode, routing.dnsTransport)
    val dnsServers = when (routing.dnsMode) {
        DnsMode.Custom -> " (${countOf(routing.customDns.size, "server")})"
        else -> ""
    }
    val ipv6 = when (routing.ipv6Mode) {
        Ipv6Mode.Enabled -> "IPv6 on"
        Ipv6Mode.Disabled -> "IPv6 off"
        Ipv6Mode.BypassOnly -> "IPv6 bypass only"
    }
    val mtu = when (routing.mtu) {
        RoutingConfig.AUTO_MTU -> "MTU auto (${RoutingConfig.resolveMtu(routing.mtu)})"
        else -> "MTU ${RoutingConfig.resolveMtu(routing.mtu)}"
    }
    val apps = when (appFilter.mode) {
        AppFilterMode.All -> "all apps"
        AppFilterMode.Allowlist -> "allowlist of ${countOf(appFilter.packages.size, "app")}"
        AppFilterMode.Blocklist -> "blocklist of ${countOf(appFilter.packages.size, "app")}"
    }
    val lan = if (routing.bypassLan) "LAN bypass on" else "LAN bypass off"
    val sources = countOf(routing.sources.count { it.source.enabled }, "direct-route source")
    return listOf(
        "DNS ${routing.dnsMode.name}$dnsServers over ${transport.label()}",
        ipv6,
        mtu,
        apps,
        lan,
        sources,
    ).joinToString(" · ")
}

fun connectionSummary(state: ConnectionState, zone: ZoneId): String = when (state) {
    is ConnectionState.Connected -> {
        val since = SINCE_FORMAT.format(Instant.ofEpochMilli(state.connectedSinceMillis).atZone(zone))
        val udp = if (state.info.udpEnabled) "UDP relay on" else "UDP relay off"
        "Connected since $since, $udp"
    }

    ConnectionState.Connecting -> "Connecting"
    is ConnectionState.Reconnecting -> "Reconnecting, attempt ${state.attempt}: ${state.reason}"
    is ConnectionState.Disconnected -> "Disconnected (${state.reason.name})"
    is ConnectionState.Error -> "Error: ${state.message}"
}

private fun DnsTransport.label(): String = when (this) {
    DnsTransport.Udp -> "UDP"
    DnsTransport.Tcp -> "TCP"
    DnsTransport.Tls -> "TLS"
    DnsTransport.Doq -> "QUIC"
    DnsTransport.Https -> "HTTPS"
    DnsTransport.Http3 -> "HTTP/3"
}

private fun countOf(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

private val SINCE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
