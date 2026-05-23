package ru.shapovalov.hysteria.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class WireConfig(
    @SerialName("server") val server: String,
    @SerialName("auth") val auth: String,
    @SerialName("tls_sni") val tlsSni: String,
    @SerialName("tls_insecure") val tlsInsecure: Boolean,
    @SerialName("tls_pin_sha256") val tlsPinSha256: String,
    @SerialName("tls_ca") val tlsCa: String,
    @SerialName("tls_client_cert") val tlsClientCert: String,
    @SerialName("tls_client_key") val tlsClientKey: String,
    @SerialName("obfs_type") val obfsType: String,
    @SerialName("obfs_password") val obfsPassword: String,
    @SerialName("init_stream_receive_window") val initStreamReceiveWindow: Long,
    @SerialName("max_stream_receive_window") val maxStreamReceiveWindow: Long,
    @SerialName("init_conn_receive_window") val initConnReceiveWindow: Long,
    @SerialName("max_conn_receive_window") val maxConnReceiveWindow: Long,
    @SerialName("max_idle_timeout") val maxIdleTimeout: Int,
    @SerialName("keep_alive_period") val keepAlivePeriod: Int,
    @SerialName("disable_pmtud") val disablePmtud: Boolean,
    @SerialName("congestion_type") val congestionType: String,
    @SerialName("bbr_profile") val bbrProfile: String,
    @SerialName("max_tx_mbps") val maxTxMbps: Int,
    @SerialName("max_rx_mbps") val maxRxMbps: Int,
    @SerialName("hop_interval") val hopInterval: Int,
    @SerialName("min_hop_interval") val minHopInterval: Int,
    @SerialName("max_hop_interval") val maxHopInterval: Int,
    @SerialName("fast_open") val fastOpen: Boolean,
    @SerialName("lazy") val lazy: Boolean,
)

fun HysteriaConfig.toJson(): String {
    val quic = quic ?: defaultQuicOptions
    val congestion = congestion ?: defaultCongestionOptions
    val bandwidth = bandwidth ?: defaultBandwidthOptions
    val transport = transport ?: defaultTransportOptions
    val behavior = behavior ?: defaultBehaviorOptions
    val obfs = obfuscation ?: ObfuscationOptions("", "")

    val wire = WireConfig(
        server = server.server,
        auth = server.auth,
        tlsSni = tls.tlsSni,
        tlsInsecure = tls.tlsInsecure,
        tlsPinSha256 = tls.tlsPinSHA256,
        tlsCa = tls.tlsCa,
        tlsClientCert = tls.tlsClientCert,
        tlsClientKey = tls.tlsClientKey,
        obfsType = obfs.obfuscationType,
        obfsPassword = obfs.obfuscationPassword,
        initStreamReceiveWindow = quic.initStreamReceiveWindow,
        maxStreamReceiveWindow = quic.maxStreamReceiveWindow,
        initConnReceiveWindow = quic.initConnReceiveWindow,
        maxConnReceiveWindow = quic.maxConnReceiveWindow,
        maxIdleTimeout = quic.maxIdleTimeoutSec,
        keepAlivePeriod = quic.keepAlivePeriodSec,
        disablePmtud = quic.disablePathMTUDiscovery,
        congestionType = congestion.congestionType,
        bbrProfile = congestion.bbrProfile,
        maxTxMbps = bandwidth.maxTxMbps,
        maxRxMbps = bandwidth.maxRxMbps,
        hopInterval = transport.hopIntervalSec,
        minHopInterval = transport.minHopIntervalSec,
        maxHopInterval = transport.maxHopIntervalSec,
        fastOpen = behavior.fastOpen,
        lazy = behavior.lazy,
    )
    return Json.encodeToString(wire)
}
