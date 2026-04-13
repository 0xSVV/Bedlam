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
        Golib.setLogHandler(listener?.let { kotlinListener ->
            LogHandler { level, message -> kotlinListener.onLog(level, message) }
        })
    }

    override suspend fun connect(config: HysteriaConfig) {
        _state.update { ConnectionState.Connecting }

        val configJson = JSONObject().apply {
            put("server", config.server)
            put("auth", config.auth)
            put("tls_sni", config.tlsSni)
            put("tls_insecure", config.tlsInsecure)
            put("disable_pmtud", config.disablePathMTUDiscovery)
            put("fast_open", config.fastOpen)
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
                    })
            } catch (e: Exception) {
                _state.update { ConnectionState.Error(e.message ?: "Unknown connection error") }
            }
        }
    }

    override fun disconnect() {
        try {
            Golib.stopClient()
        } catch (e: Exception) {
            _state.update { ConnectionState.Error(e.message ?: "Unknown error while disconnecting") }
        }
        _state.update { ConnectionState.Disconnected }
    }
}