package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.hysteria.api.DnsTransport

object DnsPresets {

    private val CLOUDFLARE = listOf(
        "1.1.1.1",
        "1.0.0.1",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001",
    )

    private val GOOGLE = listOf(
        "8.8.8.8",
        "8.8.4.4",
        "2001:4860:4860::8888",
        "2001:4860:4860::8844",
    )

    fun cloudflare(transport: DnsTransport): List<String> = CLOUDFLARE.map { endpoint(it, transport) }

    fun google(transport: DnsTransport): List<String> = GOOGLE.map { endpoint(it, transport) }

    fun supportedTransports(mode: DnsMode): List<DnsTransport> = when (mode) {
        DnsMode.System -> listOf(DnsTransport.Udp, DnsTransport.Tcp)
        else -> DnsTransport.entries
    }

    fun effectiveTransport(mode: DnsMode, transport: DnsTransport): DnsTransport =
        if (transport in supportedTransports(mode)) transport else DnsTransport.Tcp

    private fun endpoint(ip: String, transport: DnsTransport): String {
        val host = if (':' in ip) "[$ip]" else ip
        return when (transport) {
            DnsTransport.Udp, DnsTransport.Tcp -> "$host:53"
            DnsTransport.Tls -> "$host:853"
            DnsTransport.Https -> "https://$host/dns-query"
        }
    }
}
