package ru.shapovalov.hysteria.config

data class HysteriaConfig(
    val server: ServerCredentials,
    val tls: TlsOptions,
    val obfuscation: ObfuscationOptions? = null,
    val quic: QuicOptions? = null,
    val congestion: CongestionOptions? = null,
    val bandwidth: BandwidthOptions? = null,
    val transport: TransportOptions? = null,
    val behavior: BehaviorOptions? = null,
)