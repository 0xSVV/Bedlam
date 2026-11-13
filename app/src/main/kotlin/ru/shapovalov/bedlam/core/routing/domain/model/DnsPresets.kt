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
        tlsHost = "one.one.one.one",
        httpsUrl = "https://cloudflare-dns.com/dns-query",
    )

    private val GOOGLE = Provider(
        addresses = listOf(
            "8.8.8.8",
            "8.8.4.4",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
        ),
        tlsHost = "dns.google",
        httpsUrl = "https://dns.google/dns-query",
    )

    // Cisco: umbrella.cisco.com/blog/enhancing-support-dns-encryption-with-dns-over-https
    private val OPENDNS = Provider(
        addresses = listOf("208.67.222.222", "208.67.220.220", "2620:119:35::35", "2620:119:53::53"),
        tlsHost = "",
        httpsUrl = "https://doh.opendns.com/dns-query",
    )
    // AdGuard's default service filters ads, trackers and phishing domains.
    private val ADGUARD = Provider(
        addresses = listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
        tlsHost = "dns.adguard-dns.com",
        httpsUrl = "https://dns.adguard-dns.com/dns-query",
    )

    fun openDns(transport: DnsTransport): List<String> {
        require(transport in supportedTransports(DnsMode.OpenDns))
        return OPENDNS.endpoints(transport)
    }

    fun adGuard(transport: DnsTransport): List<String> {
        require(transport in supportedTransports(DnsMode.AdGuard))
        return ADGUARD.endpoints(transport)
    }

    fun openDnsAddresses(): List<String> = OPENDNS.addresses

    fun openDnsHttpsAddresses(): List<String> = listOf("146.112.41.2", "2620:119:fc::2")

    fun adGuardAddresses(): List<String> = ADGUARD.addresses

    fun cloudflare(transport: DnsTransport): List<String> = CLOUDFLARE.endpoints(transport)

    fun google(transport: DnsTransport): List<String> = GOOGLE.endpoints(transport)

    fun cloudflareAddresses(): List<String> = CLOUDFLARE.addresses

    fun googleAddresses(): List<String> = GOOGLE.addresses

    fun supportedTransports(mode: DnsMode): List<DnsTransport> = when (mode) {
        DnsMode.System -> listOf(DnsTransport.Udp, DnsTransport.Tcp)
        // Neither preset publishes a DNS over QUIC endpoint, so it stays a
        // Custom-mode choice rather than a preset that cannot answer.
        DnsMode.Cloudflare, DnsMode.Google -> DnsTransport.entries - DnsTransport.Doq
        DnsMode.OpenDns -> listOf(DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Https)
        DnsMode.AdGuard -> listOf(DnsTransport.Udp, DnsTransport.Tcp, DnsTransport.Tls, DnsTransport.Https)
        DnsMode.Custom -> DnsTransport.entries
    }

    fun effectiveTransport(mode: DnsMode, transport: DnsTransport): DnsTransport = when {
        transport in supportedTransports(mode) -> transport
        mode == DnsMode.System -> DnsTransport.Tcp
        mode == DnsMode.OpenDns -> DnsTransport.Https
        else -> DnsTransport.Tls
    }

    private class Provider(
        val addresses: List<String>,
        private val tlsHost: String,
        private val httpsUrl: String,
    ) {

        fun endpoints(transport: DnsTransport): List<String> = when (transport) {
            DnsTransport.Udp, DnsTransport.Tcp -> addresses.map { "${endpointHost(it)}:53" }
            DnsTransport.Doq -> addresses.map { "${endpointHost(it)}:853" }
            DnsTransport.Tls -> listOf("$tlsHost:853")
            DnsTransport.Https -> listOf(httpsUrl)
            DnsTransport.Http3 -> addresses.map { "https://${endpointHost(it)}/dns-query" }
        }

        private fun endpointHost(address: String): String =
            if (':' in address) "[$address]" else address
    }
}
