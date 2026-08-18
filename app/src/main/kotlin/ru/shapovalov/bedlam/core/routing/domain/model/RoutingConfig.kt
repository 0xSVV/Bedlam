package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.hysteria.api.DnsTransport
import ru.shapovalov.hysteria.api.TunConfig

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
         * The TUN always carries an IPv6 address, even when IPv6 mode is
         * Disabled: the interface has to claim `::/0` so v6 sinks into it
         * instead of leaking around the VPN. IPv6 forbids a link MTU below
         * 1280, and `VpnService.Builder.establish` fails outright with a
         * smaller one, so this is a hard floor rather than a preference.
         */
        const val MIN_TUN_MTU: Int = 1280
        const val MAX_TUN_MTU: Int = TunConfig.MAX_MTU

        fun resolveMtu(mtu: Int): Int =
            if (mtu == AUTO_MTU) MIN_TUN_MTU else mtu.coerceIn(MIN_TUN_MTU, MAX_TUN_MTU)
    }
}
