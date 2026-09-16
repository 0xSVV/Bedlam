package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.hysteria.api.DnsTransport

object DnsPresets {

    private val CLOUDFLARE = Provider(
        addresses = listOf(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
        ),
    )

    private val GOOGLE = Provider(
        addresses = listOf(
            "8.8.8.8",
            "8.8.4.4",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
        ),
    )

    fun cloudflare(transport: DnsTransport): List<String> = CLOUDFLARE.endpoints(transport)

    fun google(transport: DnsTransport): List<String> = GOOGLE.endpoints(transport)

    fun cloudflareAddresses(): List<String> = CLOUDFLARE.addresses

    fun googleAddresses(): List<String> = GOOGLE.addresses

    fun supportedTransports(mode: DnsMode): List<DnsTransport> = when (mode) {
        DnsMode.System -> listOf(DnsTransport.Udp, DnsTransport.Tcp)
        // Neither preset publishes a DNS over QUIC endpoint, so it stays a
        // Custom-mode choice rather than a preset that cannot answer.
        DnsMode.Cloudflare, DnsMode.Google -> DnsTransport.entries - DnsTransport.Doq
        DnsMode.Custom -> DnsTransport.entries
    }

    fun effectiveTransport(mode: DnsMode, transport: DnsTransport): DnsTransport = when {
        transport in supportedTransports(mode) -> transport
        mode == DnsMode.System -> DnsTransport.Tcp
        else -> DnsTransport.Tls
    }

    private class Provider(val addresses: List<String>) {

        fun endpoints(transport: DnsTransport): List<String> = when (transport) {
            DnsTransport.Udp, DnsTransport.Tcp -> addresses.map { "${endpointHost(it)}:53" }
            DnsTransport.Tls, DnsTransport.Doq -> addresses.map { "${endpointHost(it)}:853" }
            DnsTransport.Https, DnsTransport.Http3 ->
                addresses.map { "https://${endpointHost(it)}/dns-query" }
        }

        private fun endpointHost(address: String): String =
            if (':' in address) "[$address]" else address
    }
}
