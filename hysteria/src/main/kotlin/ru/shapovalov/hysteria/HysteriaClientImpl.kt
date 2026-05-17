package ru.shapovalov.hysteria

import golib.EventHandler
import golib.Golib
import golib.LogHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
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

    private val logSink = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val subscriberFloors = mutableMapOf<LogLevel, Int>()
    private val subscriberLock = Any()

    init {
        Golib.setLogHandler(LogHandler { level, message ->
            logSink.tryEmit(
                LogEntry(
                    level = parseLevel(level),
                    message = message,
                    timestampMillis = System.currentTimeMillis(),
                )
            )
        })
        Golib.setMinLogLevel(LogLevel.INFO.name)
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

                    override fun onReconnecting(attempt: Int, reason: String) {
                        _state.value = ConnectionState.Reconnecting(attempt, reason)
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
            withContext(Dispatchers.IO) { cleanup() }
            _state.value = ConnectionState.Error(e.message ?: "Start failed")
            throw e
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) { cleanup() }
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

    override fun logs(minLevel: LogLevel): Flow<LogEntry> = flow {
        logSink.filter { it.level.ordinal >= minLevel.ordinal }.collect { emit(it) }
    }
        .onStart { registerSubscriber(minLevel) }
        .onCompletion { unregisterSubscriber(minLevel) }

    private fun registerSubscriber(level: LogLevel) {
        synchronized(subscriberLock) {
            subscriberFloors[level] = (subscriberFloors[level] ?: 0) + 1
            applyNativeFloor()
        }
    }

    private fun unregisterSubscriber(level: LogLevel) {
        synchronized(subscriberLock) {
            val count = (subscriberFloors[level] ?: 0) - 1
            if (count <= 0) subscriberFloors.remove(level)
            else subscriberFloors[level] = count
            applyNativeFloor()
        }
    }

    private fun applyNativeFloor() {
        val floor = subscriberFloors.keys.minByOrNull { it.ordinal } ?: LogLevel.INFO
        Golib.setMinLogLevel(floor.name)
    }

    private fun cleanup() {
        if (tunActive.compareAndSet(true, false)) {
            runCatching { Golib.stopTUN() }
        }
        runCatching { Golib.stopClient() }
        Golib.setFdProtector(null)
    }

    private fun parseLevel(s: String): LogLevel = when (s) {
        "DEBUG" -> LogLevel.DEBUG
        "WARN" -> LogLevel.WARN
        "ERROR" -> LogLevel.ERROR
        else -> LogLevel.INFO
    }
}
