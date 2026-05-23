package ru.shapovalov.hysteria.api

data class TunConfig(
    val mtu: Int = DEFAULT_MTU,
    val ipv4Prefix: String = DEFAULT_IPV4_PREFIX,
    val ipv6Prefix: String = DEFAULT_IPV6_PREFIX,
) {
    init {
        require(mtu in MIN_MTU..MAX_MTU) { "MTU out of range: $mtu" }
        require(ipv4Prefix.contains('/')) { "ipv4Prefix is not CIDR: $ipv4Prefix" }
        require(ipv6Prefix.contains('/')) { "ipv6Prefix is not CIDR: $ipv6Prefix" }
    }

    companion object {
        const val DEFAULT_MTU: Int = 1280
        const val DEFAULT_IPV4_PREFIX: String = "172.19.0.1/30"
        const val DEFAULT_IPV6_PREFIX: String = "fdfe:dcba:9876::1/126"
        const val MIN_MTU: Int = 576
        const val MAX_MTU: Int = 9000

        val Default: TunConfig = TunConfig()
    }
}
