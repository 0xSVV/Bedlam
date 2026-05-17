package ru.shapovalov.hysteria

import golib.EventHandler
import golib.Golib
import golib.LogHandler
import golib.Session
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.toJson

class HysteriaClientImpl : HysteriaClient {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val sessionLock = Mutex()
    @Volatile private var session: Session? = null

    override suspend fun start(
        config: HysteriaConfig,
        protector: HysteriaClient.SocketProtector,
        tun: HysteriaClient.TunFactory,
    ) = sessionLock.withLock {
        when (val current = _state.value) {
            is ConnectionState.Connecting, is ConnectionState.Connected ->
                throw IllegalStateException("client already $current")
            else -> Unit
        }
        if (session != null) throw IllegalStateException("session already exists")
        _state.value = ConnectionState.Connecting

        try {
            withContext(Dispatchers.IO) {
                val s = Golib.newSession(
                    config.toJson(),
                    { fd: Int -> protector.protect(fd) },
                    object : EventHandler {
                        override fun onConnected(udpEnabled: Boolean) {
                            _state.value = ConnectionState.Connected(udpEnabled)
                        }

                        override fun onReconnecting(attempt: Int, reason: String) {
                            _state.value = ConnectionState.Reconnecting(attempt, reason)
                        }

                        override fun onError(message: String) {
                            _state.value = ConnectionState.Error(message)
                        }
                    },
                )

                val fd = tun.create(TUN_MTU)
                s.startTUN(fd, TUN_MTU, TUN_INET4_PREFIX, TUN_INET6_PREFIX)
                session = s
            }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { closeSessionLocked() }
            _state.value = ConnectionState.Error(e.message ?: "Start failed")
            throw e
        }
    }

    override suspend fun stop() {
        sessionLock.withLock {
            withContext(Dispatchers.IO) { closeSessionLocked() }
        }
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun resetConnections() {
        val s = session ?: return
        withContext(Dispatchers.IO) { runCatching { s.resetConnections() } }
    }

    override suspend fun testUdp(): String =
        withContext(Dispatchers.IO) {
            session?.testUDP() ?: "error: client not connected"
        }

    override suspend fun testDnsOverTcp(): String =
        withContext(Dispatchers.IO) {
            session?.testDNSOverTCP() ?: "error: client not connected"
        }

    override fun stats(): HysteriaClient.TrafficStats {
        val s = session ?: return HysteriaClient.TrafficStats(0, 0)
        return HysteriaClient.TrafficStats(txBytes = s.txBytes, rxBytes = s.rxBytes)
    }

    override fun logs(minLevel: LogLevel): Flow<LogEntry> = flow {
        LogSink.flow.filter { it.level.ordinal >= minLevel.ordinal }.collect { emit(it) }
    }
        .onStart { LogSink.register(minLevel) }
        .onCompletion { LogSink.unregister(minLevel) }

    private fun closeSessionLocked() {
        val s = session ?: return
        session = null
        runCatching { s.close() }
    }

    companion object {
        private const val TUN_MTU = 1280
        const val TUN_INET4_PREFIX: String = "172.19.0.1/30"
        const val TUN_INET6_PREFIX: String = "fdfe:dcba:9876::1/126"
    }
}

private object LogSink {
    val flow: MutableSharedFlow<LogEntry> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val subscriberFloors = mutableMapOf<LogLevel, Int>()
    private val lock = Any()

    init {
        Golib.setLogHandler(LogHandler { level, message ->
            flow.tryEmit(
                LogEntry(
                    level = parseLevel(level),
                    message = message,
                    timestampMillis = System.currentTimeMillis(),
                )
            )
        })
        Golib.setMinLogLevel(LogLevel.INFO.name)
    }

    fun register(level: LogLevel) {
        synchronized(lock) {
            subscriberFloors[level] = (subscriberFloors[level] ?: 0) + 1
            applyNativeFloor()
        }
    }

    fun unregister(level: LogLevel) {
        synchronized(lock) {
            val count = (subscriberFloors[level] ?: 0) - 1
            if (count <= 0) subscriberFloors.remove(level) else subscriberFloors[level] = count
            applyNativeFloor()
        }
    }

    private fun applyNativeFloor() {
        val floor = subscriberFloors.keys.minByOrNull { it.ordinal } ?: LogLevel.INFO
        Golib.setMinLogLevel(floor.name)
    }

    private fun parseLevel(s: String): LogLevel = when (s) {
        "DEBUG" -> LogLevel.DEBUG
        "WARN" -> LogLevel.WARN
        "ERROR" -> LogLevel.ERROR
        else -> LogLevel.INFO
    }
}
