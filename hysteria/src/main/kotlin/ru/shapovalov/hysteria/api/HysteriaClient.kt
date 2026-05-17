package ru.shapovalov.hysteria.api

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
 * be called from any coroutine context, including the main dispatcher.
 */
interface HysteriaClient {

    /**
     * Observable connection state. Hot flow, starts at [ConnectionState.Disconnected],
     * replays the latest value on every new collector.
     */
    val state: StateFlow<ConnectionState>

    /**
     * Brings the tunnel up.
     *
     * Suspends until the QUIC handshake completes (or fails) and the TUN device
     * is wired to the Hysteria core.
     *
     * @param config tunnel configuration.
     * @param protector invoked for every UDP socket the native layer opens, on a
     *   native worker thread, before the socket is bound. Typically delegated to
     *   [`VpnService.protect`](https://developer.android.com/reference/android/net/VpnService#protect(int)).
     *   Without protection, QUIC traffic loops back through the VPN route and stalls.
     * @param tun invoked once after the handshake succeeds; must return a raw
     *   file descriptor for an established Android TUN device. Ownership of the
     *   fd transfers to the client and is closed on [stop].
     *
     * @throws IllegalStateException if a tunnel is already running or starting.
     * @throws Exception with the original cause if the handshake fails.
     */
    suspend fun start(
        config: HysteriaConfig,
        protector: SocketProtector,
        tun: TunFactory,
    )

    /**
     * Tears the tunnel down.
     *
     * Idempotent and safe to call from any dispatcher (the blocking native cleanup
     * is dispatched internally). Returns when teardown is complete or a 3-second
     * cleanup timeout elapses, whichever is sooner.
     */
    suspend fun stop()

    /**
     * Forces live upstream sockets to close so the core re-dials on the
     * current default network. No-op if the tunnel isn't running.
     *
     * Call this on Android connectivity handoff (Wi-Fi ↔ mobile) to skip
     * the QUIC idle-timeout wait.
     */
    suspend fun resetConnections()

    /**
     * Cumulative byte counters for the current session.
     *
     * Counters reset on every successful [start]; they survive QUIC reconnects.
     * Returns zeros when no tunnel has been started this process lifetime.
     */
    fun stats(): TrafficStats

    /**
     * Cold flow of log events from the native layer at or above [minLevel].
     *
     * Subscribers are reference-counted: while at least one collector with
     * [LogLevel.DEBUG] is active, the native side emits debug-level events.
     * When the last debug collector cancels, the native floor automatically
     * rises so cheap chatter doesn't cross the JNI boundary.
     *
     * Each call returns a distinct flow that completes only when the consumer
     * cancels collection.
     */
    fun logs(minLevel: LogLevel = LogLevel.INFO): Flow<LogEntry>

    /**
     * Diagnostic: send a DNS query through the QUIC-datagram UDP relay and
     * wait for the response. Returns a human-readable status line.
     *
     * Useful for distinguishing "UDP forwarding broken at the server" from
     * "tunnel works overall" — fails when TCP-relay diagnostics succeed
     * if the server's host blocks outbound UDP.
     */
    suspend fun testUdp(): String

    /**
     * Diagnostic: send a DNS query through a hysteria TCP stream and wait
     * for the response. Returns a human-readable status line.
     */
    suspend fun testDnsOverTcp(): String

    /** Cumulative TX/RX byte counters for the current session. */
    data class TrafficStats(val txBytes: Long, val rxBytes: Long)

    /** A single log event delivered from the native layer. */
    data class LogEntry(
        val level: LogLevel,
        val message: String,
        val timestampMillis: Long,
    )

    /** Severity levels, ordered from most verbose ([DEBUG]) to most severe ([ERROR]). */
    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    /**
     * Binds native sockets to the underlying network so they don't route
     * through the VPN. Typically delegated to `VpnService.protect()`.
     *
     * @return `true` if the socket was successfully protected.
     */
    fun interface SocketProtector {
        fun protect(fd: Int): Boolean
    }

    /**
     * Establishes the Android VPN interface with the requested [mtu] and returns
     * the raw file descriptor of the resulting TUN device.
     */
    fun interface TunFactory {
        fun create(mtu: Int): Int
    }
}
