package ru.shapovalov.hysteria

import golib.EventHandler
import golib.Golib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.shapovalov.hysteria.api.HysteriaClient
import java.util.concurrent.atomic.AtomicBoolean

object HysteriaClientImpl : HysteriaClient {
    private const val TUN_MTU = 1400

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val tunActive = AtomicBoolean(false)

    override fun setLogListener(listener: HysteriaClient.LogListener?) {
        if (listener == null) {
            Golib.setLogHandler(null)
            return
        }
        Golib.setLogHandler { level, message -> listener.onLog(level, message) }
    }

    override suspend fun start(
        config: HysteriaConfig,
        protector: HysteriaClient.SocketProtector,
        tun: HysteriaClient.TunFactory,
    ) {
        when (_state.value) {
            is ConnectionState.Connecting, is ConnectionState.Connected -> return
            else -> Unit
        }
        _state.value = ConnectionState.Connecting

        try {
            withContext(Dispatchers.IO) {
                Golib.setFdProtector { fd -> protector.protect(fd) }
                Golib.startClient(config.toJson(), object : EventHandler {
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

                val fd = tun.create(TUN_MTU)
                Golib.startTUN(fd, TUN_MTU)
                tunActive.set(true)
            }
        } catch (e: Exception) {
            cleanup()
            _state.value = ConnectionState.Error(e.message ?: "Start failed")
            throw e
        }
    }

    override fun stop() {
        cleanup()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun resetConnections() {
        if (!tunActive.get()) return
        withContext(Dispatchers.IO) { runCatching { Golib.resetConnections() } }
    }

    override suspend fun testUdp(): String =
        withContext(Dispatchers.IO) { Golib.testUDP() }

    override suspend fun testDnsOverTcp(): String =
        withContext(Dispatchers.IO) { Golib.testDNSOverTCP() }

    private fun cleanup() {
        if (tunActive.compareAndSet(true, false)) {
            runCatching { Golib.stopTUN() }
        }
        runCatching { Golib.stopClient() }
        Golib.setFdProtector(null)
    }
}
