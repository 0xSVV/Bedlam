package ru.shapovalov.hysteria.api

import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaConfig

/**
 * Kotlin-native API for driving a Hysteria 2 tunnel.
 *
 * Consumers do not interact with the native layer directly — every Go call
 * is confined to the hysteria module, and this interface is the only seam.
 * Callers inject Android-specific concerns (socket protection, TUN device
 * creation) as callbacks, keeping the client transport-agnostic.
 */
interface HysteriaClient {
    /** Observable connection state. Hot flow; starts at [ConnectionState.Disconnected]. */
    val state: StateFlow<ConnectionState>

    /** Attach a log sink. Pass `null` to detach. */
    fun setLogListener(listener: LogListener?)

    /**
     * Bring the tunnel up. Suspends until the handshake completes (or fails)
     * and the TUN device is wired to the Hysteria core.
     *
     * @param protector invoked for each UDP socket the native layer opens,
     *   from a native worker thread, before dialing. Typically delegated
     *   to `VpnService.protect()`. Must be registered *before* the handshake
     *   or the QUIC traffic will loop back through the VPN route and stall.
     * @param tun invoked after the handshake succeeds; must return the raw
     *   file descriptor of an established Android TUN device. Ownership of
     *   the fd transfers to the client (closed on [stop]).
     */
    suspend fun start(
        config: HysteriaConfig,
        protector: SocketProtector,
        tun: TunFactory,
    )

    /** Tear the tunnel down. Idempotent. */
    fun stop()

    /**
     * Force-close live upstream sockets so the core re-dials on the
     * current default network. Call on Android connectivity handoff
     * (WiFi↔mobile). No-op if the tunnel isn't running.
     */
    suspend fun resetConnections()

    /** QUIC-datagram round-trip diagnostic. Returns a human-readable status line. */
    suspend fun testUdp(): String

    /** TCP-stream round-trip diagnostic. Returns a human-readable status line. */
    suspend fun testDnsOverTcp(): String

    fun interface SocketProtector {
        fun protect(fd: Int): Boolean
    }

    fun interface TunFactory {
        /** Establish a TUN device with the given MTU and return its raw fd. */
        fun create(mtu: Int): Int
    }

    interface LogListener {
        fun onLog(level: String, message: String)
    }
}
