package ru.shapovalov.hysteria.api

data class TunConfig(
    val mtu: Int = DEFAULT_MTU,
    val ipv4Prefix: String = DEFAULT_IPV4_PREFIX,
    val ipv6Prefix: String = DEFAULT_IPV6_PREFIX,
) {
    init {
        require(mtu in MIN_MTU..MAX_MTU) { "MTU out of range: $mtu" }
        requireCidr(ipv4Prefix, "ipv4Prefix", IpFamily.V4)
        requireCidr(ipv6Prefix, "ipv6Prefix", IpFamily.V6)
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

internal enum class IpFamily(val maxPrefix: Int) { V4(32), V6(128) }

internal fun requireCidr(value: String, field: String, expect: IpFamily) {
    val slash = value.indexOf('/')
    require(slash > 0 && slash == value.lastIndexOf('/')) {
        "$field is not CIDR: $value"
    }
    val host = value.substring(0, slash)
    val bits = value.substring(slash + 1).toIntOrNull()
        ?: throw IllegalArgumentException("$field has non-numeric prefix length: $value")
    require(bits in 0..expect.maxPrefix) { "$field prefix length out of range: $value" }
    val family = numericIpFamily(host)
        ?: throw IllegalArgumentException("$field is not a numeric address: $value")
    require(family == expect) { "$field address family mismatch: $value" }
}

internal fun numericIpFamily(host: String): IpFamily? = when {
    isNumericIpv4(host) -> IpFamily.V4
    isNumericIpv6(host) -> IpFamily.V6
    else -> null
}

internal fun isNumericIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() in 0..255
    }
}

internal fun isNumericIpv6(host: String): Boolean {
    if (host.isEmpty() || ':' !in host) return false
    if (host.any { c -> !c.isDigit() && c.lowercaseChar() !in 'a'..'f' && c != ':' }) return false

    val doubleColons = countDoubleColons(host)
    if (doubleColons > 1) return false
    if (host.startsWith(":") && !host.startsWith("::")) return false
    if (host.endsWith(":") && !host.endsWith("::")) return false

    val groups = host.split(':')
    val nonEmpty = groups.count { it.isNotEmpty() }
    if (doubleColons == 0) {
        if (groups.size != 8 || nonEmpty != 8) return false
    } else {
        if (nonEmpty > 7) return false
    }
    return groups.all { it.isEmpty() || it.length <= 4 }
}

private fun countDoubleColons(s: String): Int {
    var count = 0
    var i = 0
    while (i < s.length - 1) {
        if (s[i] == ':' && s[i + 1] == ':') {
            count++
            i += 2
        } else {
            i++
        }
    }
    return count
}
