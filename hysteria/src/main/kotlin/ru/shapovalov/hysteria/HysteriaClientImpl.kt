package ru.shapovalov.hysteria

import golib.EventHandler
import golib.Golib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

        withContext(Dispatchers.IO) {
            try {
                Golib.startClient(
                    config.toJson(),
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
