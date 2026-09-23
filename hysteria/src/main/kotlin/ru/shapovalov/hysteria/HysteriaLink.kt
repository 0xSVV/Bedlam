package ru.shapovalov.hysteria

import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.defaultBandwidthOptions
import ru.shapovalov.hysteria.config.defaultCongestionOptions
import ru.shapovalov.hysteria.config.defaultQuicOptions
import ru.shapovalov.hysteria.config.defaultTransportOptions
import java.util.Base64

enum class LinkConnectionGap { CustomCa, ClientCertificate, Realm }

enum class LinkTuningGap { Quic, Congestion, Bandwidth, HopInterval, ObfuscationPacketSize }

data class HysteriaLink(
    val uri: String?,
    val connectionGaps: Set<LinkConnectionGap>,
    val tuningGaps: Set<LinkTuningGap>,
    val insecureIgnoresPinElsewhere: Boolean,
)

fun buildHysteriaLink(config: HysteriaConfig, name: String): HysteriaLink {
    val realm = isRealmServer(config.server.address)
    return HysteriaLink(
        uri = if (realm) null else hysteriaUri(config, name),
        connectionGaps = connectionGaps(config, realm),
        tuningGaps = tuningGaps(config),
        insecureIgnoresPinElsewhere = config.tls.tlsInsecure && config.tls.tlsPinSHA256.isNotBlank(),
    )
}

fun splitHysteriaLinks(text: String): List<String> {
    val links = text.lines()
        .flatMap { it.split(LINK_BOUNDARY) }
        .map { it.trim() }
        .filter { entry -> LINK_SCHEMES.any { entry.startsWith(it) } }
    return links.ifEmpty { listOfNotNull(text.trim().takeIf { it.isNotEmpty() }) }
}

private fun hysteriaUri(config: HysteriaConfig, name: String): String {
    val server = splitServerAddress(config.server.address.trim())
    val hopPorts = server.ports?.takeIf { ',' in it || '-' in it }
    val port = hopPorts?.split(',', '-')?.first() ?: server.ports
    val query = sortedMapOf<String, String>()
    val tls = config.tls
    if (tls.tlsSni.isNotEmpty() && tls.tlsSni != server.host) query["sni"] = tls.tlsSni
    if (tls.tlsInsecure) query["insecure"] = "1"
    if (tls.tlsPinSHA256.isNotEmpty()) query["pinSHA256"] = normalizePin(tls.tlsPinSHA256)
    val obfs = config.obfuscation
    val obfsType = obfs?.obfuscationType?.lowercase()
    if (obfs != null && (obfsType == "salamander" || obfsType == "gecko")) {
        query["obfs"] = obfsType
        query["obfs-password"] = obfs.obfuscationPassword
    }
    if (tls.ech.isNotBlank()) query["ech"] = echConfigList(tls.ech)
    if (hopPorts != null) query["mport"] = hopPorts

    return buildString {
        append("hysteria2://")
        if (config.server.auth.isNotEmpty()) append(percentEncode(config.server.auth)).append('@')
        append(if (':' in server.host) "[${server.host}]" else server.host)
        if (port != null) append(':').append(port)
        append('/')
        if (query.isNotEmpty()) {
            append('?')
            query.entries.joinTo(this, "&") { (key, value) ->
                val kept = if (key == "mport") MPORT_LITERALS else emptySet()
                "$key=${percentEncode(value, kept)}"
            }
        }
        if (name.isNotEmpty()) append('#').append(percentEncode(name))
    }
}

private data class ServerAddress(val host: String, val ports: String?)

private fun splitServerAddress(address: String): ServerAddress {
    if (address.startsWith("[")) {
        val close = address.indexOf(']')
        if (close > 0) {
            val rest = address.substring(close + 1)
            return ServerAddress(address.substring(1, close), rest.removePrefix(":").takeIf { rest.startsWith(":") })
        }
    }
    if (address.count { it == ':' } > 1) return ServerAddress(address, null)
    val colon = address.lastIndexOf(':')
    if (colon < 0) return ServerAddress(address, null)
    return ServerAddress(address.substring(0, colon), address.substring(colon + 1))
}

private fun connectionGaps(config: HysteriaConfig, realm: Boolean): Set<LinkConnectionGap> = buildSet {
    if (config.tls.tlsCa.isNotBlank()) add(LinkConnectionGap.CustomCa)
    if (config.tls.tlsClientCert.isNotBlank() || config.tls.tlsClientKey.isNotBlank()) {
        add(LinkConnectionGap.ClientCertificate)
    }
    if (realm) add(LinkConnectionGap.Realm)
}

private fun tuningGaps(config: HysteriaConfig): Set<LinkTuningGap> = buildSet {
    if ((config.quic ?: defaultQuicOptions) != defaultQuicOptions) add(LinkTuningGap.Quic)
    if ((config.congestion ?: defaultCongestionOptions) != defaultCongestionOptions) add(LinkTuningGap.Congestion)
    if ((config.bandwidth ?: defaultBandwidthOptions) != defaultBandwidthOptions) add(LinkTuningGap.Bandwidth)
    if ((config.transport ?: defaultTransportOptions) != defaultTransportOptions) add(LinkTuningGap.HopInterval)
    val obfs = config.obfuscation
    if (obfs != null && obfs.obfuscationType.equals("gecko", ignoreCase = true) &&
        (obfs.geckoMinPacketSize != 0 || obfs.geckoMaxPacketSize != 0)
    ) {
        add(LinkTuningGap.ObfuscationPacketSize)
    }
}

private fun isRealmServer(address: String): Boolean {
    val scheme = address.trim().substringBefore("://", missingDelimiterValue = "")
    return scheme == "realm" || scheme.startsWith("realm+")
}

private fun normalizePin(pin: String): String = pin.lowercase().replace(":", "").replace("-", "")

private fun echConfigList(ech: String): String {
    val body = ech.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("-----") }
        .joinToString("")
    val decoded = runCatching { Base64.getDecoder().decode(body) }
        .recoverCatching { Base64.getUrlDecoder().decode(body) }
        .getOrNull()
    return decoded?.let { Base64.getEncoder().encodeToString(it) } ?: body
}

private fun percentEncode(text: String, kept: Set<Char> = emptySet()): String = buildString {
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c.isUnreserved() || c in kept) {
            append(c)
        } else {
            append('%').append(HEX_DIGITS[c.code shr 4]).append(HEX_DIGITS[c.code and 0xF])
        }
    }
}

private fun Char.isUnreserved(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'

private const val HEX_DIGITS = "0123456789ABCDEF"

private val MPORT_LITERALS = setOf(',')

private val LINK_SCHEMES = listOf("hysteria2://", "hy2://")

private val LINK_BOUNDARY = Regex("""(?<=\s)(?=(?:hysteria2|hy2)://)""")
