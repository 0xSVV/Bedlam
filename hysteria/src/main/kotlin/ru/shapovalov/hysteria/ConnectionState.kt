package ru.shapovalov.hysteria

import ru.shapovalov.hysteria.api.ConnectionInfo
import ru.shapovalov.hysteria.api.DisconnectReason

/**
 * Lifecycle state of a Hysteria tunnel. Emitted on the [HysteriaClient.state]
 * StateFlow. Transitions are linear: `Disconnected → Connecting → Connected
 * ↔ Reconnecting`, with `Error` reachable from any state on a fatal startup
 * failure and `Disconnected(USER|REVOKED)` reachable from any state on an
 * orderly stop.
 */
sealed interface ConnectionState {
    /** No active tunnel. Carries the reason if a previous session ended. */
    data class Disconnected(val reason: DisconnectReason = DisconnectReason.NEVER_STARTED) :
        ConnectionState

    /** Initial dial in progress. */
    data object Connecting : ConnectionState

    /** Tunnel is live and traffic flows through it. */
    data class Connected(val info: ConnectionInfo, val connectedSinceMillis: Long) : ConnectionState

    /** A live connection was lost; reconnect is in progress. */
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState

    /** Initial startup failed irrecoverably. The session is gone. */
    data class Error(val message: String) : ConnectionState
}
