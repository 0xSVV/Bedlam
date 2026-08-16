package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.hysteria.api.DnsTransport

data class RoutingConfig(
    val bypassLan: Boolean = true,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.Enabled,
    val dnsMode: DnsMode = DnsMode.Cloudflare,
    val dnsTransport: DnsTransport = DnsTransport.Tcp,
    val customDns: List<String> = emptyList(),
    val sources: List<ResolvedSource> = emptyList(),
)
