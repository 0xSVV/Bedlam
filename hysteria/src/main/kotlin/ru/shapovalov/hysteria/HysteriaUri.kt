package ru.shapovalov.hysteria

import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ObfuscationOptions
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import ru.shapovalov.hysteria.config.defaultTlsOptions
import java.net.URLDecoder

/**
 * Result of parsing a Hysteria 2 URI: a fully-formed [HysteriaConfig] plus the
 * human-readable [name] taken from the URI fragment (empty if absent).
 */
data class ParsedHysteriaUri(
    val config: HysteriaConfig,
    val name: String,
)

/**
 * Parses a Hysteria 2 URI into a [ParsedHysteriaUri].
 *
 * Format: `hysteria2://[auth@]hostname[:port]/?[key=value]&...[#name]`
 * Also accepts the `hy2://` scheme.
 *
 * Supported query parameters: sni, insecure, pinSHA256, obfs, obfs-password.
 * The optional fragment is surfaced as [ParsedHysteriaUri.name].
 *
 * @see <a href="https://v2.hysteria.network/docs/developers/URI-Scheme/">Hysteria 2 URI Scheme</a>
 */
fun parseHysteriaUri(uriString: String): ParsedHysteriaUri {
    val raw = uriString.trim()
    val schemeEnd = raw.indexOf("://")
    require(schemeEnd > 0) { "URI must start with hysteria2:// or hy2://" }
    val scheme = raw.substring(0, schemeEnd)
    require(scheme == "hysteria2" || scheme == "hy2") {
        "URI must start with hysteria2:// or hy2://"
    }

    val body = raw.substring(schemeEnd + 3)

    val fragmentIdx = body.indexOf('#')
    val beforeFragment = if (fragmentIdx < 0) body else body.substring(0, fragmentIdx)
    val rawFragment = if (fragmentIdx < 0) "" else body.substring(fragmentIdx + 1)

    val queryIdx = beforeFragment.indexOf('?')
    val beforeQuery = if (queryIdx < 0) beforeFragment else beforeFragment.substring(0, queryIdx)
    val rawQuery = if (queryIdx < 0) "" else beforeFragment.substring(queryIdx + 1)

    val pathIdx = beforeQuery.indexOf('/')
    val authority = if (pathIdx < 0) beforeQuery else beforeQuery.substring(0, pathIdx)

    val atIdx = authority.lastIndexOf('@')
    val rawUserInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
    val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
    val auth = URLDecoder.decode(rawUserInfo, "UTF-8")

    val parsedHost = parseHostPort(hostPort)
    require(parsedHost.host.isNotEmpty()) { "URI must contain a hostname" }
    val server = when {
        parsedHost.isHopping -> hostPort
        ':' in parsedHost.host -> "[${parsedHost.host}]:${parsedHost.port}"
        else -> "${parsedHost.host}:${parsedHost.port}"
    }

    val params = parseQuery(rawQuery)
    val sniParam = params["sni"].orEmpty()
    val sni = sniParam.ifEmpty { if (isIpLiteral(parsedHost.host)) "" else parsedHost.host }
    val insecure = params["insecure"] == "1"
    val pinSHA256 = params["pinSHA256"].orEmpty()
    val obfs = params["obfs"].orEmpty()
    val obfsPassword = params["obfs-password"].orEmpty()
    val name = URLDecoder.decode(rawFragment, "UTF-8")

    val config = HysteriaConfig(
        server = ServerCredentials(address = server, auth = auth),
        tls = TlsOptions(
            tlsSni = sni,
            tlsInsecure = insecure,
            tlsPinSHA256 = pinSHA256,
            tlsCa = defaultTlsOptions.tlsCa,
            tlsClientCert = defaultTlsOptions.tlsClientCert,
            tlsClientKey = defaultTlsOptions.tlsClientKey,
        ),
        obfuscation = ObfuscationOptions(
            obfuscationType = obfs,
            obfuscationPassword = obfsPassword,
        )
    )
    return ParsedHysteriaUri(config = config, name = name)
}

private data class HostPort(val host: String, val port: Int, val isHopping: Boolean)

private fun parseHostPort(hostPort: String): HostPort {
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        require(close > 0) { "unclosed bracketed IPv6 in URI" }
        val host = hostPort.substring(1, close)
        val rest = hostPort.substring(close + 1)
        return parsePortAfterHost(host, rest)
    }
    val colon = hostPort.indexOf(':')
    if (colon < 0) return HostPort(hostPort, DEFAULT_PORT, isHopping = false)
    val host = hostPort.substring(0, colon)
    return parsePortAfterHost(host, hostPort.substring(colon))
}

private fun parsePortAfterHost(host: String, rest: String): HostPort {
    if (rest.isEmpty()) return HostPort(host, DEFAULT_PORT, isHopping = false)
    require(rest.startsWith(":")) { "expected ':' between host and port" }
    val portStr = rest.substring(1)
    if (',' in portStr || '-' in portStr) {
        return HostPort(host, 0, isHopping = true)
    }
    val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
    return HostPort(host, port, isHopping = false)
}

private fun parseQuery(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val idx = pair.indexOf('=')
        val key = if (idx < 0) pair else pair.substring(0, idx)
        val value = if (idx < 0) "" else pair.substring(idx + 1)
        result[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
    }
    return result
}

private fun isIpLiteral(host: String): Boolean {
    if (host.isEmpty()) return false
    if (':' in host) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

private const val DEFAULT_PORT = 443
