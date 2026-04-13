package ru.shapovalov.hysteria.internal

import golib.EventHandler
import golib.Golib
import golib.LogHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
            put("server", config.server)
            put("auth", config.auth)
            put("tls_sni", config.tlsSni)
            put("tls_insecure", config.tlsInsecure)
            put("tls_ca", config.tlsCa)
            put("disable_pmtud", config.disablePathMTUDiscovery)
            put("fast_open", config.fastOpen)
            put("max_tx_mbps", config.maxTxMbps)
            put("max_rx_mbps", config.maxRxMbps)
        }.toString()

        withContext(Dispatchers.IO) {
            try {
                Golib.startClient(
                    configJson,
                    config.socksAddr,
                    config.httpAddr,
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