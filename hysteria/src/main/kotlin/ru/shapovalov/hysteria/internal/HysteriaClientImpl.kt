package ru.shapovalov.hysteria.internal

import golib.EventHandler
import golib.Golib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.shapovalov.hysteria.api.HysteriaClient

class HysteriaClientImpl : HysteriaClient {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    override fun setLogListener(listener: HysteriaClient.LogListener?) {
        if (listener == null) {
            Golib.setLogHandler(null)
            return
        }

        Golib.setLogHandler { level, message ->
            listener.onLog(level, message)
        }
    }

    override suspend fun connect(config: HysteriaConfig) {
        if (_state.value is ConnectionState.Connecting || _state.value is ConnectionState.Connected) {
            return
        }

        _state.value = ConnectionState.Connecting

        val configJson = JSONObject().apply {
            put("server", config.server.server)
            put("auth", config.server.auth)
            put("tls_sni", config.tls.tlsSni)
            put("tls_insecure", config.tls.tlsInsecure)
            put("tls_pin_sha256", config.tls.tlsPinSHA256)
            put("tls_ca", config.tls.tlsCa)
            put("tls_client_cert", config.tls.tlsClientCert)
            put("tls_client_key", config.tls.tlsClientKey)
            put("obfs_type", config.obfuscation.obfuscationType)
            put("obfs_password", config.obfuscation.obfuscationPassword)
            put("init_stream_receive_window", config.quic.initStreamReceiveWindow)
            put("max_stream_receive_window", config.quic.maxStreamReceiveWindow)
            put("init_conn_receive_window", config.quic.initConnReceiveWindow)
            put("max_conn_receive_window", config.quic.maxConnReceiveWindow)
            put("max_idle_timeout", config.quic.maxIdleTimeoutSec)
            put("keep_alive_period", config.quic.keepAlivePeriodSec)
            put("disable_pmtud", config.quic.disablePathMTUDiscovery)
            put("congestion_type", config.congestion.congestionType)
            put("bbr_profile", config.congestion.bbrProfile)
            put("max_tx_mbps", config.bandwidth.maxTxMbps)
            put("max_rx_mbps", config.bandwidth.maxRxMbps)
            put("hop_interval", config.transport.hopIntervalSec)
            put("min_hop_interval", config.transport.minHopIntervalSec)
            put("max_hop_interval", config.transport.maxHopIntervalSec)
            put("fast_open", config.behavior.fastOpen)
            put("lazy", config.behavior.lazy)
            put("socks_addr", config.socks.socksAddress)
            put("socks_username", config.socks.socksUsername)
            put("socks_password", config.socks.socksPassword)
            put("socks_disable_udp", config.socks.socksDisableUDP)
            put("http_addr", config.http.httpAddress)
            put("http_username", config.http.httpUsername)
            put("http_password", config.http.httpPassword)
        }.toString()

        withContext(Dispatchers.IO) {
            try {
                Golib.startClient(
                    configJson,
                    object : EventHandler {
                        override fun onConnected(udpEnabled: Boolean) {
                            _state.value = ConnectionState.Connected(udpEnabled)
                        }

                        override fun onDisconnected(reason: String) {
                            _state.value = ConnectionState.Disconnected
                        }

                        override fun onError(message: String) {
                            _state.value = ConnectionState.Error(message)
                        }
                    }
                )
            } catch (e: Exception) {
                _state.value = ConnectionState.Error(e.message ?: "Native link failure")
            }
        }
    }

    override fun disconnect() {
        runCatching { Golib.stopClient() }
        _state.value = ConnectionState.Disconnected
    }
}
