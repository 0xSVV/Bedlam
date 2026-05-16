package ru.shapovalov.hysteria.api

import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.config.HysteriaConfig

/**
 * Kotlin-native API for driving a Hysteria 2 tunnel.
 *
 * Consumers do not interact with the native layer directly — every Go call
 * is confined to the hysteria module, and this interface is the only seam.
 * Callers inject Android-specific concerns (socket protection, TUN device
 * creation) as callbacks via [SocketProtector] and [TunFactory], keeping
 * the client transport-agnostic.
 */
interface HysteriaClient {
    /**
     * Observable connection state. Hot flow; starts at [ConnectionState.Disconnected].
     */
    val state: StateFlow<ConnectionState>

    /**
     * Attaches a [LogListener] to act as a log sink. Pass `null` to detach.
     */
    fun setLogListener(listener: LogListener?)

    /**
     * Filters log output below [level] before it crosses the native boundary.
     * Defaults to `INFO`. Accepts: `DEBUG`, `INFO`, `WARN`, `ERROR`.
     */
    fun setMinLogLevel(level: String)

    /**
     * Brings the tunnel up.
     *
     * Suspends until the handshake completes (or fails) and the TUN device is wired
     * to the Hysteria core.
     *
     * @param config The configuration payload for the Hysteria tunnel.
     * @param protector Invoked for each UDP socket the native layer opens, from a native
     *   worker thread, before dialing. Typically delegated to `VpnService.protect()`.
     *   Must be registered *before* the handshake or the QUIC traffic will loop back
     *   through the VPN route and stall.
     * @param tun Invoked after the handshake succeeds; its [TunFactory.create] method
     *   must return the raw file descriptor of an established Android TUN device.
     *   Ownership of the fd transfers to the client (closed on [stop]).
     */
    suspend fun start(
        config: HysteriaConfig,
        protector: SocketProtector,
        tun: TunFactory,
    )

    /**
     * Tears the tunnel down. Idempotent.
     */
    fun stop()

    /**
     * Forces live upstream sockets to close so the core re-dials on the
     * current default network.
     *
     * Call on Android connectivity handoff (WiFi ↔ mobile). No-op if the tunnel
     * isn't currently running.
     */
    suspend fun resetConnections()

    /**
     * Executes a QUIC-datagram round-trip diagnostic.
     *
     * @return A human-readable status line.
     */
    suspend fun testUdp(): String

    /**
     * Executes a TCP-stream round-trip diagnostic.
     *
     * @return A human-readable status line.
     */
    suspend fun testDnsOverTcp(): String

    /**
     * Callback interface to bind native sockets to the underlying network.
     *
     * The [protect] method is called to prevent routing loops, returning `true`
     * if the socket with the given file descriptor was successfully protected.
     */
    fun interface SocketProtector {
        fun protect(fd: Int): Boolean
    }

    /**
     * Factory for establishing the Android VPN interface.
     *
     * The [create] method must establish a TUN device utilizing the requested MTU.
     * and return its raw file descriptor.
     */
    fun interface TunFactory {
        fun create(mtu: Int): Int
    }

    /**
     * Sink for native logging output.
     *
     * The [onLog] method is invoked synchronously by the core with the severity
     * level and formatted message.
     */
    interface LogListener {
        fun onLog(level: String, message: String)
    }
}
