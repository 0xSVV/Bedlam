package ru.shapovalov.bedlam.core.routing.domain.model

data class RoutingConfig(
    val bypassLan: Boolean = true,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.Enabled,
    val dnsMode: DnsMode = DnsMode.Cloudflare,
    val customDns: List<String> = emptyList(),
    val sources: List<ResolvedSource> = emptyList(),
)
