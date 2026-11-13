package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.DnsServer
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.hysteria.api.DirectDnsConfig
import ru.shapovalov.hysteria.api.DirectDnsDomain
import ru.shapovalov.hysteria.api.DnsTransport
import java.net.URI

internal object DirectDnsPlanner {
    fun plan(config: RoutingConfig, systemServers: List<String>): DirectDnsConfig {
        val mode = config.directDnsMode
        val transport = config.directDnsTransport
        require(transport in listOf(DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Tls, DnsTransport.Https)) {
            "Unsupported Direct DNS transport"
        }
        require((mode != DnsMode.System) || (transport in listOf(DnsTransport.Udp, DnsTransport.Tcp))) {
            "System Direct DNS supports UDP or TCP"
        }
        require(transport in DnsPresets.supportedTransports(mode)) { "Unsupported transport for Direct DNS provider" }
        val rawServers = when (mode) {
            DnsMode.System -> systemServers
            DnsMode.Cloudflare -> DnsPresets.cloudflare(transport)
            DnsMode.Google -> DnsPresets.google(transport)
            DnsMode.OpenDns -> DnsPresets.openDns(transport)
            DnsMode.AdGuard -> DnsPresets.adGuard(transport)
            DnsMode.Custom -> config.directCustomDns
        }
        require((mode == DnsMode.System) || rawServers.isNotEmpty()) { "Direct DNS needs at least one server" }
        val servers = rawServers.map { raw ->
            requireNotNull(DnsServer.normalizeOrNull(raw, transport)) { "Invalid Direct DNS server: $raw" }
        }
        val configuredBootstrap = config.directDnsBootstrap.mapKeys { (key) -> key.lowercase() }
        val bootstrap = buildMap {
            for (endpoint in servers) {
                if (DnsServer.literalHostOf(endpoint) != null) continue
                val host = URI(if (endpoint.startsWith("https://")) endpoint else "tls://$endpoint").host.lowercase()
                val addresses = when (mode) {
                    DnsMode.Cloudflare -> DnsPresets.cloudflareAddresses()
                    DnsMode.Google -> DnsPresets.googleAddresses()
                    DnsMode.OpenDns -> DnsPresets.openDnsHttpsAddresses()
                    DnsMode.AdGuard -> DnsPresets.adGuardAddresses()
                    else -> configuredBootstrap[host].orEmpty()
                }
                require(addresses.isNotEmpty()) { "Direct DNS server $host needs bootstrap IP addresses" }
                put(host, addresses)
            }
        }
        return DirectDnsConfig(
            transport = transport,
            servers = servers,
            bootstrap = bootstrap,
            domains = config.sources.mapNotNull { (source) ->
                val domain = source as? DirectRouteSource.Domain
                if ((domain != null) && domain.enabled) DirectDnsDomain(domain.id, domain.hostname) else null
            },
        )
    }
}
