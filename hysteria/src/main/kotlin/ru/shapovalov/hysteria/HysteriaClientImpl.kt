package ru.shapovalov.hysteria

import android.os.ParcelFileDescriptor
import android.util.Log
import golib.EventHandler
import golib.Golib
import golib.LogHandler
import golib.Session
import golib.TestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val lifecycleLock = Any()
    private val liveGeneration = AtomicLong(0)

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
        val generation = liveGeneration.incrementAndGet()
        try {
            withContext(Dispatchers.IO) {
                val s = openSession(config, protector, generation)
                val published = synchronized(lifecycleLock) {
                    if (liveGeneration.get() == generation) {
                        session = s
                        true
                    } else {
                        false
                    }
                }
                if (!published) {
                    runCatching { s.close() }
                    throw CancellationException("client shut down during start")
                }
                attachTun(s, tunConfig, tun)
            }
            markTunReady(generation)
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
        generation: Long,
    ): Session = Golib.newSession(
        config.toJson(),
        { fd: Int -> protector.protect(fd) },
        sessionEventHandler(generation),
    )

    private fun sessionEventHandler(generation: Long): EventHandler = object : EventHandler {
        override fun onConnected(udpEnabled: Boolean, attempt: Int) {
            synchronized(lifecycleLock) {
                if (liveGeneration.get() != generation) return
                val info = ConnectionInfo(serverAddress, udpEnabled, attempt)
                lastConnectInfo.set(info)
                if (tunReady) {
                    if (sessionStartMillis == 0L) sessionStartMillis = System.currentTimeMillis()
                    _state.value = ConnectionState.Connected(info, sessionStartMillis)
                } else {
                    pendingConnect.set(info)
                }
            }
        }

        override fun onReconnecting(attempt: Int, reason: String) {
            synchronized(lifecycleLock) {
                if (liveGeneration.get() != generation) return
                _state.value = ConnectionState.Reconnecting(attempt, reason)
            }
        }

        override fun onDisconnected(reason: String) {
            synchronized(lifecycleLock) {
                if (liveGeneration.get() != generation) return
                _state.value = ConnectionState.Error(reason)
            }
        }
    }

    override suspend fun updateTun(
        tunConfig: TunConfig,
        tun: HysteriaClient.TunFactory,
    ): Unit = sessionLock.withLock {
        val s = session ?: throw IllegalStateException("no active session")
        withContext(Dispatchers.IO) {
            attachTun(s, tunConfig, tun, replaceExisting = true)
        }
    }

    private fun attachTun(
        session: Session,
        tunConfig: TunConfig,
        factory: HysteriaClient.TunFactory,
        replaceExisting: Boolean = false,
    ) {
        val pfd = factory.create(tunConfig)
        val fd = try {
            pfd.detachFd()
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
        try {
            if (replaceExisting) {
                runCatching { session.stopTUN() }.onFailure {
                    Log.w(TAG, "Stopping previous TUN failed", it)
                }
            }
            session.startTUN(
                fd,
                tunConfig.mtu,
                TunConfig.IPV4_CIDR,
                TunConfig.IPV6_CIDR,
                tunConfig.ipv6Enabled,
            )
        } catch (t: Throwable) {
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
            throw t
        }
    }

    private fun markTunReady(generation: Long) {
        synchronized(lifecycleLock) {
            if (liveGeneration.get() != generation) return
            tunReady = true
            pendingConnect.getAndSet(null)?.let {
                if (sessionStartMillis == 0L) sessionStartMillis = System.currentTimeMillis()
                _state.value = ConnectionState.Connected(it, sessionStartMillis)
            }
        }
    }

    private suspend fun abortStart(cause: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { closeSessionLocked() }
        if (cause !is CancellationException) {
            _state.value = ConnectionState.Error(cause.message ?: "Start failed")
        }
    }

    override suspend fun stop(reason: DisconnectReason) {
        sessionLock.withLock {
            withContext(NonCancellable + Dispatchers.IO) { closeSessionLocked() }
            tunReady = false
            sessionStartMillis = 0L
            pendingConnect.set(null)
            lastConnectInfo.set(null)
            _state.value = ConnectionState.Disconnected(reason)
            LogSink.clearReplay()
        }
    }

    override suspend fun closeSession() = sessionLock.withLock {
        withContext(NonCancellable + Dispatchers.IO) { closeSessionLocked() }
        tunReady = false
        sessionStartMillis = 0L
        pendingConnect.set(null)
        lastConnectInfo.set(null)
    }

    override fun shutdown(reason: DisconnectReason) {
        val s = invalidateSession()
        tunReady = false
        sessionStartMillis = 0L
        pendingConnect.set(null)
        lastConnectInfo.set(null)
        _state.value = ConnectionState.Disconnected(reason)
        if (s != null) {
            runCatching { s.close() }.onFailure { Log.w(TAG, "Session close on shutdown failed", it) }
        }
        LogSink.clearReplay()
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

    private fun invalidateSession(): Session? = synchronized(lifecycleLock) {
        liveGeneration.incrementAndGet()
        val current = session
        session = null
        current
    }

    private fun closeSessionLocked() {
        val s = invalidateSession() ?: return
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
    private val attached = AtomicBoolean(false)

    fun attach() {
        if (!attached.compareAndSet(false, true)) return
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

    fun clearReplay() {
        flow.resetReplayCache()
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
