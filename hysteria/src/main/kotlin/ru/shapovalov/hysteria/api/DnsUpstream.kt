package ru.shapovalov.hysteria.api

/**
 * How the native layer forwards DNS queries that arrive at the on-TUN
 * resolver ([TunConfig.IPV4_DNS_ADDRESS] / [TunConfig.IPV6_DNS_ADDRESS]).
 * Every transport goes through the Hysteria tunnel; host names in server
 * addresses are resolved by the Hysteria server.
 */
enum class DnsTransport(val wire: String) {
    Udp("udp"),
    Tcp("tcp"),
    Tls("tls"),
    Https("https"),
}

/**
 * The upstream resolvers behind the on-TUN DNS server, tried in order.
 *
 * Server syntax depends on [transport]: `ip[:port]` or `[v6][:port]` for
 * [DnsTransport.Udp]/[DnsTransport.Tcp] (default port 53), `host|ip[:port]`
 * for [DnsTransport.Tls] (default 853), and an `https://` URL or a bare host
 * (expanded to `https://<host>/dns-query`) for [DnsTransport.Https].
 */
data class DnsUpstream(
    val transport: DnsTransport,
    val servers: List<String>,
) {
    init {
        require(servers.isNotEmpty()) { "at least one DNS server required" }
    }

    companion object {
        val Default: DnsUpstream = DnsUpstream(DnsTransport.Tcp, listOf("1.1.1.1:53", "1.0.0.1:53"))
    }
}
