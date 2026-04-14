package ru.shapovalov.hysteria.internal

data class HysteriaConfig(
    val server: ServerCredentials,
    val tls: TlsOptions,
    val obfuscation: ObfuscationOptions,
    val quic: QuicOptions,
    val congestion: CongestionOptions,
    val bandwidth: BandwidthOptions,
    val transport: TransportOptions,
    val behavior: BehaviorOptions,
    val socks: SocksOptions,
    val http: HttpOptions
)

data class ServerCredentials(
    val server: String,
    val auth: String,
)
data class TlsOptions(
    val tlsSni: String = "",
    val tlsInsecure: Boolean = false,
    val tlsPinSHA256: String = "",
    val tlsCa: String = "",
    val tlsClientCert: String = "",
    val tlsClientKey: String = "",
)

data class ObfuscationOptions(
    val obfuscationType: String = "",
    val obfuscationPassword: String = "",
)

data class QuicOptions(
    val initStreamReceiveWindow: Long = 0,
    val maxStreamReceiveWindow: Long = 0,
    val initConnReceiveWindow: Long = 0,
    val maxConnReceiveWindow: Long = 0,
    val maxIdleTimeoutSec: Int = 0,
    val keepAlivePeriodSec: Int = 0,
    val disablePathMTUDiscovery: Boolean = false,
)

data class CongestionOptions(
    val congestionType: String = "",
    val bbrProfile: String = "",
)

data class BandwidthOptions(
    val maxTxMbps: Int = 0,
    val maxRxMbps: Int = 0,
)

data class TransportOptions(
    val hopIntervalSec: Int = 0,
    val minHopIntervalSec: Int = 0,
    val maxHopIntervalSec: Int = 0,
)

data class BehaviorOptions(
    val fastOpen: Boolean = false,
    val lazy: Boolean = false,
)

data class SocksOptions(
    val socksAddress: String = "127.0.0.1:1080",
    val socksUsername: String = "",
    val socksPassword: String = "",
    val socksDisableUDP: Boolean = false,
)

data class HttpOptions(
    val httpAddress: String = "",
    val httpUsername: String = "",
    val httpPassword: String = "",
)