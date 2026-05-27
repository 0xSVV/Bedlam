package ru.shapovalov.hysteria

import android.os.ParcelFileDescriptor
import android.util.Log
import golib.EventHandler
import golib.Golib
import golib.LogHandler
import golib.Session
import golib.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.shapovalov.hysteria.api.ConnectionInfo
import ru.shapovalov.hysteria.api.DiagnosticResult
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import ru.shapovalov.hysteria.api.TunConfig
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.toJson
import java.util.concurrent.atomic.AtomicReference

class HysteriaClientImpl : HysteriaClient {

    init {
        LogSink.attach()
    }

    private val _state = MutableStateFlow<ConnectionState>(
        ConnectionState.Disconnected(DisconnectReason.NEVER_STARTED)
    )
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    private var session: Session? = null
    private val sessionLock = Mutex()

    @Volatile
    private var serverAddress: String = ""
    @Volatile
    private var tunReady: Boolean = false
    @Volatile
    private var sessionStartMillis: Long = 0L

    private val pendingConnect = AtomicReference<ConnectionInfo?>(null)
    private val lastConnectInfo = AtomicReference<ConnectionInfo?>(null)

    override suspend fun start(
        config: HysteriaConfig,
        tunConfig: TunConfig,
        protector: HysteriaClient.SocketProtector,
        tun: HysteriaClient.TunFactory,
    ): Unit = sessionLock.withLock {
        requireNotStarted()
        beginConnecting(config)
        try {
            withContext(Dispatchers.IO) {
                val s = openSession(config, protector)
                session = s
                attachTun(s, tunConfig, tun)
            }
            markTunReady()
        } catch (e: Exception) {
            abortStart(e)
            throw e
        }
    }

    private fun requireNotStarted() {
        when (val current = _state.value) {
            is ConnectionState.Connecting,
            is ConnectionState.Connected,
            is ConnectionState.Reconnecting -> throw IllegalStateException("client already $current")

            is ConnectionState.Disconnected, is ConnectionState.Error -> Unit
        }
        if (session != null) throw IllegalStateException("session already exists")
    }

    private fun beginConnecting(config: HysteriaConfig) {
        _state.value = ConnectionState.Connecting
        serverAddress = config.server.address
        tunReady = false
        sessionStartMillis = 0L
        pendingConnect.set(null)
    }

    private fun openSession(
        config: HysteriaConfig,
        protector: HysteriaClient.SocketProtector,
    ): Session = Golib.newSession(
        config.toJson(),
        { fd: Int -> protector.protect(fd) },
        sessionEventHandler(),
    )

    private fun sessionEventHandler(): EventHandler = object : EventHandler {
        override fun onConnected(udpEnabled: Boolean, attempt: Int) {
            val info = ConnectionInfo(serverAddress, udpEnabled, attempt)
            lastConnectInfo.set(info)
            if (tunReady) {
                if (sessionStartMillis == 0L) sessionStartMillis = System.currentTimeMillis()
                _state.value = ConnectionState.Connected(info, sessionStartMillis)
            } else {
                pendingConnect.set(info)
            }
        }

        override fun onReconnecting(attempt: Int, reason: String) {
            _state.value = ConnectionState.Reconnecting(attempt, reason)
        }

        override fun onError(message: String) {
            _state.value = ConnectionState.Error(message)
        }
    }

    private fun attachTun(
        session: Session,
        tunConfig: TunConfig,
        factory: HysteriaClient.TunFactory,
    ) {
        val pfd = factory.create(tunConfig)
        val fd = try {
            pfd.detachFd()
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
        try {
            session.startTUN(fd, tunConfig.mtu, TunConfig.IPV4_CIDR, TunConfig.IPV6_CIDR)
        } catch (t: Throwable) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw t
        }
    }

    private fun markTunReady() {
        tunReady = true
        pendingConnect.getAndSet(null)?.let {
            if (sessionStartMillis == 0L) sessionStartMillis = System.currentTimeMillis()
            _state.value = ConnectionState.Connected(it, sessionStartMillis)
        }
    }

    private suspend fun abortStart(cause: Exception) {
        withContext(Dispatchers.IO) { closeSessionLocked() }
        _state.value = ConnectionState.Error(cause.message ?: "Start failed")
    }

    override suspend fun stop(reason: DisconnectReason) {
        sessionLock.withLock {
            withContext(Dispatchers.IO) { closeSessionLocked() }
        }
        tunReady = false
        sessionStartMillis = 0L
        pendingConnect.set(null)
        lastConnectInfo.set(null)
        _state.value = ConnectionState.Disconnected(reason)
    }

    override suspend fun resetConnections() {
        val s = session ?: return
        withContext(Dispatchers.IO) { runCatching { s.resetConnections() } }
    }

    override fun stats(): HysteriaClient.TrafficStats? {
        val s = session ?: return null
        return HysteriaClient.TrafficStats(txBytes = s.txBytes, rxBytes = s.rxBytes)
    }

    override fun validateConfig(config: HysteriaConfig): Result<Unit> = runCatching {
        val json = config.toJson()
        Golib.validateConfig(json)
    }

    override suspend fun testUdp(): DiagnosticResult =
        withContext(Dispatchers.IO) {
            val s = session ?: return@withContext DiagnosticResult.Error("client not connected")
            s.testUDP().toDiagnosticResult()
        }

    override suspend fun testDnsOverTcp(): DiagnosticResult =
        withContext(Dispatchers.IO) {
            val s = session ?: return@withContext DiagnosticResult.Error("client not connected")
            s.testDNSOverTCP().toDiagnosticResult()
        }

    override fun logs(minLevel: LogLevel): Flow<LogEntry> = LogSink.flow
        .filter { it.level.ordinal >= minLevel.ordinal }
        .onStart { LogSink.register(minLevel) }
        .onCompletion { LogSink.unregister(minLevel) }

    private fun closeSessionLocked() {
        val s = session ?: return
        session = null
        runCatching { s.close() }.onFailure {
            Log.w(TAG, "Session close failed", it)
        }
    }

    companion object {
        private const val TAG = "HysteriaClient"
    }
}

private fun TestResult.toDiagnosticResult(): DiagnosticResult =
    if (ok) {
        DiagnosticResult.Ok(bytes = bytes, rttMillis = elapsedMs, target = detail)
    } else {
        DiagnosticResult.Error(error)
    }

private object LogSink {
    val flow: MutableSharedFlow<LogEntry> = MutableSharedFlow(
        replay = 256,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val subscriberFloors = mutableMapOf<LogLevel, Int>()
    private val lock = Any()

    fun attach() {
        Golib.setLogHandler(LogHandler { level, source, message ->
            flow.tryEmit(
                LogEntry(
                    level = parseLevel(level),
                    source = source ?: "",
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
        "INFO" -> LogLevel.INFO
        else -> {
            Log.w("HysteriaLog", "Unknown native log level '$s' — coercing to INFO")
            LogLevel.INFO
        }
    }
}
