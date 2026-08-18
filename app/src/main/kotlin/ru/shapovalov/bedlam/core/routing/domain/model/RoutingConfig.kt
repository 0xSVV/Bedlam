package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.hysteria.api.DnsTransport

data class RoutingConfig(
    val bypassLan: Boolean = true,
    val ipv6Mode: Ipv6Mode = Ipv6Mode.Enabled,
    val dnsMode: DnsMode = DnsMode.Cloudflare,
    val dnsTransport: DnsTransport = DnsTransport.Tcp,
    val mtu: Int = AUTO_MTU,
    val customDns: List<String> = emptyList(),
    val sources: List<ResolvedSource> = emptyList(),
) {
    companion object {
        const val AUTO_MTU: Int = 0

        /**
         * Hysteria carries a relayed UDP datagram in one QUIC datagram of at
         * most 1200 bytes, so a 1280-byte interface splits every full-size
         * packet a browser sends over HTTP/3 into two halves that are dropped
         * whole if either is lost. Going below 1280 is only legal without
         * IPv6, which sets that as its minimum link MTU.
         */
        const val AUTO_MTU_V4_ONLY: Int = 1220
        const val AUTO_MTU_WITH_IPV6: Int = 1280

        fun resolveMtu(mtu: Int, ipv6Enabled: Boolean): Int = when {
            mtu == AUTO_MTU -> if (ipv6Enabled) AUTO_MTU_WITH_IPV6 else AUTO_MTU_V4_ONLY
            // IPv6 forbids a link MTU below 1280. The editor refuses such a
            // value, but the IPv6 mode can change after one was saved.
            ipv6Enabled -> maxOf(mtu, AUTO_MTU_WITH_IPV6)
            else -> mtu
        }
    }
}
