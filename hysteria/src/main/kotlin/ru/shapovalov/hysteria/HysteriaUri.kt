package ru.shapovalov.hysteria

import android.net.Uri
import androidx.core.net.toUri
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ObfuscationOptions
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import ru.shapovalov.hysteria.config.defaultTlsOptions
import java.net.URLDecoder

/**
 * Parses a Hysteria 2 URI into a [HysteriaConfig].
 *
 * Format: `hysteria2://[auth@]hostname[:port]/?[key=value]&...[#name]`
 * Also accepts the `hy2://` scheme.
 *
 * Supported query parameters: sni, insecure, pinSHA256, obfs, obfs-password.
 * Optional fragment is treated as a human-readable connection name.
 *
 * @see <a href="https://v2.hysteria.network/docs/developers/URI-Scheme/">Hysteria 2 URI Scheme</a>
 */
fun parseHysteriaUri(uriString: String): HysteriaConfig {
    val raw = uriString.trim()
    require(raw.startsWith("hysteria2://") || raw.startsWith("hy2://")) {
        "URI must start with hysteria2:// or hy2://"
    }

    val uri = raw.toUri()
    val auth = uri.encodedUserInfo?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

    val host = requireNotNull(uri.host) { "URI must contain a hostname" }
    val port = uri.port.takeIf { it > 0 } ?: 443
    val server = resolveServerAddress(uri, host, port)

    val sniParam = uri.getQueryParameter("sni").orEmpty()
    val sni = sniParam.ifEmpty { if (isIpLiteral(host)) "" else host }
    val insecure = uri.getQueryParameter("insecure") == "1"
    val pinSHA256 = uri.getQueryParameter("pinSHA256").orEmpty()
    val obfs = uri.getQueryParameter("obfs").orEmpty()
    val obfsPassword = uri.getQueryParameter("obfs-password").orEmpty()
    val name = uri.encodedFragment?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

    return HysteriaConfig(
        server = ServerCredentials(server = server, auth = auth),
        name = name,
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
}

private fun isIpLiteral(host: String): Boolean {
    if (host.isEmpty()) return false
    if (host.startsWith("[") && host.endsWith("]")) return true // bracketed IPv6
    if (":" in host) return true // bare IPv6
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
}

private fun resolveServerAddress(uri: Uri, parsedHost: String, parsedPort: Int): String {
    val authority = uri.encodedAuthority ?: return "$parsedHost:$parsedPort"
    val hostPort = if ("@" in authority) authority.substringAfter("@") else authority

    // Skip past a bracketed IPv6 literal before locating the port separator.
    val portSepStart = if (hostPort.startsWith("[")) hostPort.indexOf(']') + 1 else 0
    if (portSepStart < 0) return "$parsedHost:$parsedPort"

    val lastColon = hostPort.indexOf(':', startIndex = portSepStart)
    if (lastColon > 0) {
        val portPart = hostPort.substring(lastColon + 1)
        if (',' in portPart || '-' in portPart) {
            return hostPort
        }
    }

    return "$parsedHost:$parsedPort"
}
