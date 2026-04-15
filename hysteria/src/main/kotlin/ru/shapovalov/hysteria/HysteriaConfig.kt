package ru.shapovalov.hysteria

import org.json.JSONObject

data class HysteriaConfig(
    val server: ServerCredentials,
    val tls: TlsOptions,
    val obfuscation: ObfuscationOptions,
    val quic: QuicOptions,
    val congestion: CongestionOptions,
    val bandwidth: BandwidthOptions,
    val transport: TransportOptions,
    val behavior: BehaviorOptions,
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


fun HysteriaConfig.toJson(): String = JSONObject().apply {
    put("server", server.server)
    put("auth", server.auth)
    put("tls_sni", tls.tlsSni)
    put("tls_insecure", tls.tlsInsecure)
    put("tls_pin_sha256", tls.tlsPinSHA256)
    put("tls_ca", tls.tlsCa)
    put("tls_client_cert", tls.tlsClientCert)
    put("tls_client_key", tls.tlsClientKey)
    put("obfs_type", obfuscation.obfuscationType)
    put("obfs_password", obfuscation.obfuscationPassword)
    put("init_stream_receive_window", quic.initStreamReceiveWindow)
    put("max_stream_receive_window", quic.maxStreamReceiveWindow)
    put("init_conn_receive_window", quic.initConnReceiveWindow)
    put("max_conn_receive_window", quic.maxConnReceiveWindow)
    put("max_idle_timeout", quic.maxIdleTimeoutSec)
    put("keep_alive_period", quic.keepAlivePeriodSec)
    put("disable_pmtud", quic.disablePathMTUDiscovery)
    put("congestion_type", congestion.congestionType)
    put("bbr_profile", congestion.bbrProfile)
    put("max_tx_mbps", bandwidth.maxTxMbps)
    put("max_rx_mbps", bandwidth.maxRxMbps)
    put("hop_interval", transport.hopIntervalSec)
    put("min_hop_interval", transport.minHopIntervalSec)
    put("max_hop_interval", transport.maxHopIntervalSec)
    put("fast_open", behavior.fastOpen)
    put("lazy", behavior.lazy)
}.toString()