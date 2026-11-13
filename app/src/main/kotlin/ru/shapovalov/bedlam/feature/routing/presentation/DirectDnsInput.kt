package ru.shapovalov.bedlam.feature.routing.presentation

import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.engine.DirectDnsPlanner
import ru.shapovalov.hysteria.api.DirectDnsConfig
import ru.shapovalov.hysteria.api.DnsTransport

/** Parses an unsaved editor draft, using the same validation as VPN startup. */
internal object DirectDnsInput {
    fun parse(mode: DnsMode, transport: DnsTransport, servers: String, bootstrap: String): DirectDnsConfig {
        val addresses = buildMap {
            if (mode == DnsMode.Custom && transport in listOf(DnsTransport.Tls, DnsTransport.Https)) {
                bootstrap.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                    val parts = line.split('=', limit = 2)
                    require(parts.size == 2) { "Bootstrap entries need hostname=IP addresses" }
                    val host = parts[0].trim().lowercase()
                    require(host.isNotEmpty() && host !in this) { "Duplicate or empty bootstrap hostname" }
                    put(host, parts[1].split(',', ' ', '\t').filter(String::isNotBlank))
                }
            }
        }
        return DirectDnsPlanner.plan(
            RoutingConfig(
                directDnsMode = mode,
                directDnsTransport = transport,
                directCustomDns = servers.split(',', '\n').map(String::trim).filter(String::isNotEmpty),
                directDnsBootstrap = addresses,
            ),
            systemServers = emptyList(),
        )
    }
}
