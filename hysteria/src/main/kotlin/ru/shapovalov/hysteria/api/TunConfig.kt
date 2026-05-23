package ru.shapovalov.hysteria.api

import android.net.InetAddresses
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class TunConfig(
    val mtu: Int = DEFAULT_MTU,
    val ipv4Prefix: String = DEFAULT_IPV4_PREFIX,
    val ipv6Prefix: String = DEFAULT_IPV6_PREFIX,
) {
    init {
        require(mtu in MIN_MTU..MAX_MTU) { "MTU out of range: $mtu" }
        requireCidr(ipv4Prefix, "ipv4Prefix", maxBits = 32, expect = Inet4Address::class.java)
        requireCidr(ipv6Prefix, "ipv6Prefix", maxBits = 128, expect = Inet6Address::class.java)
    }

    companion object {
        const val DEFAULT_MTU: Int = 1280
        const val DEFAULT_IPV4_PREFIX: String = "172.19.0.1/30"
        const val DEFAULT_IPV6_PREFIX: String = "fdfe:dcba:9876::1/126"
        const val MIN_MTU: Int = 576
        const val MAX_MTU: Int = 9000

        val Default: TunConfig = TunConfig()

        private fun requireCidr(
            value: String,
            field: String,
            maxBits: Int,
            expect: Class<out InetAddress>,
        ) {
            val slash = value.indexOf('/')
            require(slash > 0 && slash == value.lastIndexOf('/')) {
                "$field is not CIDR: $value"
            }
            val host = value.substring(0, slash)
            val bits = value.substring(slash + 1).toIntOrNull()
                ?: throw IllegalArgumentException("$field has non-numeric prefix length: $value")
            require(bits in 0..maxBits) { "$field prefix length out of range: $value" }
            require(InetAddresses.isNumericAddress(host)) {
                "$field is not a numeric address: $value"
            }
            val parsed = InetAddresses.parseNumericAddress(host)
            require(expect.isInstance(parsed)) { "$field address family mismatch: $value" }
        }
    }
}
