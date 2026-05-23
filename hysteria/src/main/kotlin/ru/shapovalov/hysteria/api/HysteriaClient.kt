package ru.shapovalov.hysteria.api

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.config.HysteriaConfig

/**
 * Kotlin-native API for driving a Hysteria 2 tunnel.
 *
 * Consumers do not interact with the native layer directly — every Go call
 * is confined to the hysteria module, and this interface is the only seam.
 * Android-specific concerns (socket protection, TUN device creation) are
 * injected as callbacks via [SocketProtector] and [TunFactory], keeping
 * this surface transport-agnostic.
 *
 * All suspending methods dispatch their blocking work internally; they may
 * be called from any coroutine context, including [kotlinx.coroutines.Dispatchers.Main].
 */
interface HysteriaClient {

    /**
     * Observable connection state. Hot flow, starts at [ConnectionState.Disconnected]
     * with [DisconnectReason.NEVER_STARTED]. StateFlow deduplicates by equality, so
     * the same value is never emitted twice in a row.
     */
    val state: StateFlow<ConnectionState>

    /**
     * Brings the tunnel up.
     *
     * Suspends until the QUIC handshake completes (or fails) and the TUN device
     * is wired to the Hysteria core. Concurrent calls are rejected.
     *
     * @param config tunnel configuration.
     * @param protector invoked for every UDP socket the native layer opens, on a
     *   native worker thread, before the socket is bound. Typically delegated to
     *   [`VpnService.protect`](https://developer.android.com/reference/android/net/VpnService#protect(int)).
     *   Returning `false` is logged (`WARN`) and may indicate a routing loop is
     *   imminent.
     * @param tun invoked once after the handshake succeeds; must return a
     *   [ParcelFileDescriptor] for an established Android TUN device. The
     *   client takes ownership: the fd is detached and passed to the native
     *   layer on success, or closed if [start] fails before the native layer
     *   adopts it.
     *
     * @throws IllegalStateException if a tunnel is already running or starting.
     * @throws Exception with the original cause if the handshake fails.
     */
    suspend fun start(
        config: HysteriaConfig,
        tunConfig: TunConfig = TunConfig.Default,
        protector: SocketProtector,
        tun: TunFactory,
    )

    /**
     * Tears the tunnel down. Idempotent and safe to call from any dispatcher.
     * Returns when teardown is complete or a 3-second cleanup timeout elapses.
     *
     * @param reason recorded in the [ConnectionState.Disconnected] emission that
     *   follows. Defaults to [DisconnectReason.USER]; pass [DisconnectReason.REVOKED]
     *   from a `VpnService.onRevoke()` handler.
     */
    suspend fun stop(reason: DisconnectReason = DisconnectReason.USER)

    /**
     * Forces live upstream sockets to close so the core re-dials on the
     * current default network. No-op if the tunnel isn't running.
     *
     * Call this on Android connectivity handoff (Wi-Fi ↔ mobile) to skip
     * the QUIC idle-timeout wait.
     */
    suspend fun resetConnections()

    /**
     * Cumulative byte counters for the current session, or `null` if no
     * session is active. Counters reset on every successful [start]; they
     * survive QUIC reconnects.
     */
    fun stats(): TrafficStats?

    /**
     * Cold flow of log events from the native layer at or above [minLevel].
     *
     * Subscribers are reference-counted: while at least one collector wants
     * a more verbose level, the JNI floor follows the most permissive
     * subscriber. When the last subscriber at that level cancels, the floor
     * rises automatically.
     *
     * The underlying buffer replays the most recent 256 events to every new
     * collector, so a late-attached log viewer immediately sees recent
     * context.
     */
    fun logs(minLevel: LogLevel = LogLevel.INFO): Flow<LogEntry>

    /**
     * Validates [config] without dialing or doing DNS. Returns success if the
     * configuration is structurally sound (well-formed server address, parseable
     * TLS material, supported obfuscation type, etc.). Cheap to call from a
     * config editor on every keystroke.
     */
    fun validateConfig(config: HysteriaConfig): Result<Unit>

    /**
     * Diagnostic: send a DNS query through the QUIC-datagram UDP relay and
     * wait for the response. Distinguishes "UDP forwarding broken at the
     * server" from "tunnel works overall".
     */
    suspend fun testUdp(): DiagnosticResult

    /** Diagnostic: send a DNS query through a hysteria TCP stream. */
    suspend fun testDnsOverTcp(): DiagnosticResult

    /** Cumulative TX/RX byte counters for the current session. */
    data class TrafficStats(val txBytes: Long, val rxBytes: Long)

    /** A single log event delivered from the native layer. */
    data class LogEntry(
        val level: LogLevel,
        val source: String,
        val message: String,
        val timestampMillis: Long,
    )

    /**
     * Severity levels, ordered from most verbose ([DEBUG]) to most severe
     * ([ERROR]). Comparison uses [Enum.ordinal] — do not reorder.
     */
    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    /**
     * Binds native sockets to the underlying network so they don't route
     * through the VPN. Called from a native goroutine, before the socket
     * is bound. Typically delegated to `VpnService.protect()`.
     *
     * @return `true` if the socket was successfully protected.
     */
    fun interface SocketProtector {
        fun protect(fd: Int): Boolean
    }

    /**
     * Establishes the Android VPN interface with the requested [mtu] and returns
     * the resulting TUN device. Ownership passes to the client; do not close
     * the returned descriptor yourself.
     */
    fun interface TunFactory {
        fun create(mtu: Int): ParcelFileDescriptor
    }
}

/** Snapshot of negotiated connection parameters, exposed in [ConnectionState.Connected]. */
data class ConnectionInfo(
    /** Server address as the client resolved and dialed it. */
    val serverAddress: String,
    /** Whether the server advertised UDP-relay support in the handshake. */
    val udpEnabled: Boolean,
    /** Number of failed attempts that preceded this successful connect (0 on first connect). */
    val attempt: Int,
)

/** Why a session ended. Recorded in [ConnectionState.Disconnected]. */
enum class DisconnectReason {
    /** No session has been started yet in this process. */
    NEVER_STARTED,

    /** User invoked [HysteriaClient.stop]. */
    USER,

    /** Android revoked the VPN permission. */
    REVOKED,
}

/** Outcome of a connectivity diagnostic ([HysteriaClient.testUdp] / [HysteriaClient.testDnsOverTcp]). */
sealed interface DiagnosticResult {
    data class Ok(
        val bytes: Int,
        val rttMillis: Long,
        val target: String,
    ) : DiagnosticResult

    data class Error(val reason: String) : DiagnosticResult
}
