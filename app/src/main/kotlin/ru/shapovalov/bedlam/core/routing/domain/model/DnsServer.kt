package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.hysteria.api.DnsTransport
import java.net.URI

sealed interface DnsServerParse {
    data class Valid(val normalized: String) : DnsServerParse
    data class Invalid(val error: DnsServerError) : DnsServerParse
}

enum class DnsServerError {
    Empty,
    InvalidAddress,
    InvalidPort,
    PortNotAllowed,
    HostNotAllowed,
    InvalidUrl,
    NotHttps,
}

object DnsServer {

    fun parse(raw: String, transport: DnsTransport): DnsServerParse {
        val value = raw.trim()
        if (value.isEmpty()) return DnsServerParse.Invalid(DnsServerError.Empty)
        return when (transport) {
            DnsTransport.Udp, DnsTransport.Tcp -> parseHostPort(value, defaultPort = 53, allowHost = false)
            DnsTransport.Tls -> parseHostPort(value, defaultPort = 853, allowHost = true)
            DnsTransport.Https, DnsTransport.Http3 -> parseDohUrl(value)
        }
    }

    fun normalizeOrNull(raw: String, transport: DnsTransport): String? =
        (parse(raw, transport) as? DnsServerParse.Valid)?.normalized

    /**
     * The IP literal a normalized endpoint points at, or null when it names a
     * host. Used to keep resolver addresses inside the tunnel.
     */
    fun literalHostOf(normalized: String): String? {
        val authority = when {
            normalized.startsWith("https://") ->
                normalized.removePrefix("https://").substringBefore('/')

            else -> normalized
        }
        val host = when {
            authority.startsWith("[") -> authority.substring(1, authority.indexOf(']').takeIf { it > 0 } ?: return null)
            else -> authority.substringBeforeLast(':', authority)
        }
        return when (classifyHost(host, bracketed = authority.startsWith("["))) {
            HostKind.V4, HostKind.V6 -> host
            else -> null
        }
    }

    private fun parseHostPort(value: String, defaultPort: Int, allowHost: Boolean): DnsServerParse {
        val (host, portText) = splitHostPort(value)
            ?: return DnsServerParse.Invalid(DnsServerError.InvalidAddress)
        val port = when (portText) {
            null -> defaultPort
            else -> portText.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return DnsServerParse.Invalid(DnsServerError.InvalidPort)
        }
        val kind = classifyHost(host, bracketed = value.startsWith("["))
            ?: return DnsServerParse.Invalid(DnsServerError.InvalidAddress)
        if (kind == HostKind.Name && !allowHost) {
            return DnsServerParse.Invalid(DnsServerError.HostNotAllowed)
        }
        return DnsServerParse.Valid("${kind.render(host)}:$port")
    }

    private fun parseDohUrl(value: String): DnsServerParse {
        if ("://" !in value) {
            val (host, portText) = splitHostPort(value)
                ?: return DnsServerParse.Invalid(DnsServerError.InvalidAddress)
            // A port on the bare form is ambiguous — `1.1.1.1:53` left over
            // from a plain-DNS transport would silently become
            // https://1.1.1.1:53/dns-query. Ports go in a full URL.
            if (portText != null) return DnsServerParse.Invalid(DnsServerError.PortNotAllowed)
            val kind = classifyHost(host, bracketed = value.startsWith("["))
                ?: return DnsServerParse.Invalid(DnsServerError.InvalidAddress)
            return DnsServerParse.Valid("https://${kind.render(host)}/dns-query")
        }
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return DnsServerParse.Invalid(DnsServerError.InvalidUrl)
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return DnsServerParse.Invalid(DnsServerError.NotHttps)
        }
        val host = uri.host ?: return DnsServerParse.Invalid(DnsServerError.InvalidUrl)
        if (uri.rawUserInfo != null) return DnsServerParse.Invalid(DnsServerError.InvalidUrl)
        if (uri.port != -1 && uri.port !in 1..65535) {
            return DnsServerParse.Invalid(DnsServerError.InvalidPort)
        }
        if (classifyHost(host.removePrefix("[").removeSuffix("]"), bracketed = host.startsWith("[")) == null) {
            return DnsServerParse.Invalid(DnsServerError.InvalidUrl)
        }
        val portPart = if (uri.port == -1) "" else ":${uri.port}"
        val rawPath = uri.rawPath.orEmpty()
        val path = if (rawPath.isEmpty() || rawPath == "/") "/dns-query" else rawPath
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return DnsServerParse.Valid("https://$host$portPart$path$query")
    }

    private fun splitHostPort(value: String): Pair<String, String?>? {
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return null
            val host = value.substring(1, end)
            val rest = value.substring(end + 1)
            return when {
                rest.isEmpty() -> host to null
                rest.startsWith(":") -> host to rest.substring(1)
                else -> null
            }
        }
        return when (value.count { it == ':' }) {
            0 -> value to null
            1 -> value.substringBefore(':') to value.substringAfter(':')
            else -> value to null
        }
    }

    private enum class HostKind {
        V4, V6, Name;

        fun render(host: String): String = if (this == V6) "[$host]" else host
    }

    private fun classifyHost(host: String, bracketed: Boolean): HostKind? = when {
        host.isEmpty() -> null
        ':' in host -> runCatching { parseIpv6ToBytes(host) }.map { HostKind.V6 }.getOrNull()
        bracketed -> null
        host.all { it.isDigit() || it == '.' } ->
            runCatching { parseIpv4ToBytes(host) }.map { HostKind.V4 }.getOrNull()

        isHostName(host) -> HostKind.Name
        else -> null
    }

    private fun isHostName(host: String): Boolean {
        if (host.length > 253) return false
        val labels = host.removeSuffix(".").split('.')
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it.code < 128 && (it.isLetterOrDigit() || it == '-') }
        }
    }
}
