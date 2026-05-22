package ru.shapovalov.bedlam.core.routing.domain.model

/** User-visible routing configuration. */
data class RoutingConfig(
    val bypassLan: Boolean = true,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.Enabled,
    val dnsMode: DnsMode = DnsMode.Cloudflare,
    val customDns: List<String> = emptyList(),
    val directRoutes: List<DirectRouteRule> = emptyList(),
    val geoDirectCountries: Set<CountryCode> = emptySet(),
)
