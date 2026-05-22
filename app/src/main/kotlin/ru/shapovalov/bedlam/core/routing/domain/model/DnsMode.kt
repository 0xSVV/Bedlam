package ru.shapovalov.bedlam.core.routing.domain.model

enum class DnsMode {
    /** No DNS server installed on the TUN; queries leak to the underlying network. */
    System,
    Cloudflare,
    Google,

    /** User-supplied list lives in [RoutingConfig.customDns]. */
    Custom,
}
