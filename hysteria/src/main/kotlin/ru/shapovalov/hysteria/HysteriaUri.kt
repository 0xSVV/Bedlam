package ru.shapovalov.hysteria

import android.net.Uri
import androidx.core.net.toUri

/**
 * Parses a Hysteria 2 URI into a [HysteriaConfig].
 *
 * Format: `hysteria2://[auth@]hostname[:port]/?[key=value]&...`
 * Also accepts the `hy2://` scheme.
 *
 * Supported query parameters: sni, insecure, pinSHA256, obfs, obfs-password
 *
 * @see <a href="https://v2.hysteria.network/docs/developers/URI-Scheme/">Hysteria 2 URI Scheme</a>
 */
fun parseHysteriaUri(uriString: String): HysteriaConfig {
    val raw = uriString.trim()
    require(raw.startsWith("hysteria2://") || raw.startsWith("hy2://")) {
        "URI must start with hysteria2:// or hy2://"
    }

    val uri = raw.toUri()
    val auth = uri.userInfo.orEmpty()

    val host = requireNotNull(uri.host) { "URI must contain a hostname" }
    val port = uri.port.takeIf { it > 0 } ?: 443
    val server = resolveServerAddress(uri, host, port)

    val sni = uri.getQueryParameter("sni").orEmpty()
    val insecure = uri.getQueryParameter("insecure") == "0"
    val pinSHA256 = uri.getQueryParameter("pinSHA256").orEmpty()
    val obfs = uri.getQueryParameter("obfs").orEmpty()
    val obfsPassword = uri.getQueryParameter("obfs-password").orEmpty()

    return HysteriaConfig(
        server = ServerCredentials(server = server, auth = auth),
        tls = TlsOptions(
            tlsSni = sni,
            tlsInsecure = insecure,
            tlsPinSHA256 = pinSHA256,
        ),
        obfuscation = ObfuscationOptions(
            obfuscationType = obfs,
            obfuscationPassword = obfsPassword,
        ),
        quic = QuicOptions(),
        congestion = CongestionOptions(),
        bandwidth = BandwidthOptions(),
        transport = TransportOptions(),
        behavior = BehaviorOptions(),
    )
}

private fun resolveServerAddress(uri: Uri, parsedHost: String, parsedPort: Int): String {
    val authority = uri.encodedAuthority ?: return "$parsedHost:$parsedPort"
    val hostPort = if ("@" in authority) authority.substringAfter("@") else authority

    val lastColon = hostPort.lastIndexOf(':')
    if (lastColon > 0) {
        val portPart = hostPort.substring(lastColon + 1)
        if (',' in portPart || '-' in portPart) {
            return hostPort
        }
    }

    return "$parsedHost:$parsedPort"
}
