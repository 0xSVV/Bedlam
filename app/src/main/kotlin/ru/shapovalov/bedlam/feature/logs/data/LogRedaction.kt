package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.parseIpv4ToBytes
import ru.shapovalov.bedlam.core.routing.domain.model.parseIpv6ToBytes
import ru.shapovalov.bedlam.core.routing.engine.CidrMath
import ru.shapovalov.bedlam.core.util.isRealmAddress
import ru.shapovalov.bedlam.core.util.parseHost
import ru.shapovalov.hysteria.api.TunConfig
import java.net.URI

data class RedactionRules(
    val keptAddresses: List<String> = emptyList(),
    val hostNames: Set<String> = emptySet(),
)

fun redactionRules(profiles: List<Profile>): RedactionRules = RedactionRules(
    keptAddresses = listOf(
        TunConfig.IPV4_ADDRESS,
        TunConfig.IPV6_ADDRESS,
        TunConfig.IPV4_DNS_ADDRESS,
        TunConfig.IPV6_DNS_ADDRESS,
    ) + DnsPresets.cloudflareAddresses() + DnsPresets.googleAddresses(),
    hostNames = profiles.mapNotNull { serverHostName(it.config.server.address) }.toSet(),
)

fun redactAddresses(text: String, rules: RedactionRules): String {
    val kept = rules.keptAddresses.mapNotNull { addressBytes(it)?.let(::addressKey) }.toSet()
    val addressTokens = HashMap<String, String>()
    val withoutAddresses = ADDRESS_PATTERN.replace(text) { match ->
        val bytes = addressBytes(match.value) ?: return@replace match.value
        val key = addressKey(bytes)
        if (key in kept || !isPublic(bytes)) {
            match.value
        } else {
            addressTokens.getOrPut(key) { "<ip-${addressTokens.size + 1}>" }
        }
    }
    if (rules.hostNames.isEmpty()) return withoutAddresses
    val hostTokens = HashMap<String, String>()
    val hostPattern = Regex(
        "(?<![A-Za-z0-9.-])(?:" +
                rules.hostNames.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
                ")(?![A-Za-z0-9-]|\\.[A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    return hostPattern.replace(withoutAddresses) { match ->
        hostTokens.getOrPut(match.value.lowercase()) { "<host-${hostTokens.size + 1}>" }
    }
}

private fun serverHostName(address: String): String? {
    val trimmed = address.trim()
    val host = if (isRealmAddress(trimmed)) {
        runCatching { URI(trimmed).host }.getOrNull()
    } else {
        parseHost(trimmed)
    } ?: return null
    val name = host.removeSuffix(".").lowercase()
    val isName = name.isNotEmpty() &&
            name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' } &&
            name.any { it in 'a'..'z' }
    return name.takeIf { isName }
}

private fun addressBytes(literal: String): ByteArray? = runCatching {
    if (':' !in literal) return@runCatching parseIpv4ToBytes(literal)
    val tail = literal.substringAfterLast(':')
    if ('.' !in tail) return@runCatching parseIpv6ToBytes(literal)
    val v4 = parseIpv4ToBytes(tail)
    val high = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
    val low = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
    parseIpv6ToBytes(literal.substringBeforeLast(':') + ":" + high.toString(16) + ":" + low.toString(16))
}.getOrNull()

private fun addressKey(bytes: ByteArray): String {
    val mapped = bytes.size == 16 && CidrMath.contains(IPV4_MAPPED, Cidr.V6(bytes, 128))
    val canonical = if (mapped) bytes.copyOfRange(12, 16) else bytes
    return canonical.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

private fun isPublic(bytes: ByteArray): Boolean {
    if (bytes.size == 4) return NON_PUBLIC_V4.none { CidrMath.contains(it, Cidr.V4(bytes, 32)) }
    val host = Cidr.V6(bytes, 128)
    if (CidrMath.contains(IPV4_MAPPED, host)) return isPublic(bytes.copyOfRange(12, 16))
    return NON_PUBLIC_V6.none { CidrMath.contains(it, host) }
}

private val ADDRESS_PATTERN = Regex(
    "(?<![0-9A-Za-z_:.])(?:[0-9A-Fa-f]{0,4}:){2,7}(?:[0-9A-Fa-f]{1,4}|\\d{1,3}(?:\\.\\d{1,3}){3})?" +
            "(?![0-9A-Za-z_:]|\\.\\d)" +
            "|(?<![0-9A-Za-z_.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![0-9A-Za-z_]|\\.\\d)"
)

private val IPV4_MAPPED = Cidr.parse("::ffff:0:0/96")

private val NON_PUBLIC_V4 = listOf(
    "0.0.0.0/8",
    "10.0.0.0/8",
    "100.64.0.0/10",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "224.0.0.0/3",
).map(Cidr::parse)

private val NON_PUBLIC_V6 = listOf(
    "::/127",
    "fc00::/7",
    "fe80::/10",
    "ff00::/8",
).map(Cidr::parse)
