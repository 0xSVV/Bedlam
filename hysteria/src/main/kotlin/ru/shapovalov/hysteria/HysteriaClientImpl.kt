package ru.shapovalov.hysteria

import golib.EventHandler
import golib.Golib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.toJson
import java.util.concurrent.atomic.AtomicBoolean

object HysteriaClientImpl : HysteriaClient {
    private const val TUN_MTU = 1280

    const val TUN_INET4_PREFIX: String = "172.19.0.1/30"
    const val TUN_INET6_PREFIX: String = "fdfe:dcba:9876::1/126"

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

    override fun setMinLogLevel(level: String) {
        Golib.setMinLogLevel(level)
    }

    override suspend fun start(
        config: HysteriaConfig,
        protector: HysteriaClient.SocketProtector,
        tun: HysteriaClient.TunFactory,
    ) {
        when (val current = _state.value) {
            is ConnectionState.Connecting, is ConnectionState.Connected ->
                throw IllegalStateException("client already $current")
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
                Golib.startTUN(fd, TUN_MTU, TUN_INET4_PREFIX, TUN_INET6_PREFIX)
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

    override fun stats(): HysteriaClient.TrafficStats =
        HysteriaClient.TrafficStats(
            txBytes = Golib.getTxBytes(),
            rxBytes = Golib.getRxBytes(),
        )

    private fun cleanup() {
        if (tunActive.compareAndSet(true, false)) {
            runCatching { Golib.stopTUN() }
        }
        runCatching { Golib.stopClient() }
        Golib.setFdProtector(null)
    }
}
