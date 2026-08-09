package ru.shapovalov.bedlam.core.vpn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.ConnectionInfo
import ru.shapovalov.hysteria.api.DisconnectReason

class VpnRuntimeStateTest {

    private val now = 1_000_000L
    private val disconnected = ConnectionState.Disconnected(DisconnectReason.USER)
    private val connected = ConnectionState.Connected(
        info = ConnectionInfo("host.example:443", udpEnabled = true, attempt = 0),
        connectedSinceMillis = now,
    )

    @Nested
    inner class HeartbeatFreshness {

        @Test
        fun `a never-written heartbeat is stale`() {
            assertFalse(VpnRuntimeState(heartbeatAtMillis = 0L).isHeartbeatFresh(now))
        }

        @Test
        fun `a heartbeat inside the window is fresh`() {
            val state = VpnRuntimeState(
                heartbeatAtMillis = now - VpnRuntimeState.HEARTBEAT_STALE_AFTER_MS + 1
            )
            assertTrue(state.isHeartbeatFresh(now))
        }

        @Test
        fun `a heartbeat exactly at the window edge is still fresh`() {
            val state = VpnRuntimeState(
                heartbeatAtMillis = now - VpnRuntimeState.HEARTBEAT_STALE_AFTER_MS
            )
            assertTrue(state.isHeartbeatFresh(now))
        }

        @Test
        fun `a heartbeat past the window is stale`() {
            val state = VpnRuntimeState(
                heartbeatAtMillis = now - VpnRuntimeState.HEARTBEAT_STALE_AFTER_MS - 1
            )
            assertFalse(state.isHeartbeatFresh(now))
        }
    }

    @Nested
    inner class ExpectsActiveTunnel {

        @Test
        fun `only recoverable statuses expect a tunnel`() {
            val recoverable = setOf(
                VpnRuntimeStatus.Starting,
                VpnRuntimeStatus.Running,
                VpnRuntimeStatus.Interrupted,
            )
            VpnRuntimeStatus.entries.forEach { status ->
                val state = VpnRuntimeState(desiredRunning = true, status = status)
                assertEquals(status in recoverable, state.expectsActiveTunnel, status.name)
            }
        }

        @Test
        fun `nothing is expected when the user did not ask for a tunnel`() {
            val state = VpnRuntimeState(desiredRunning = false, status = VpnRuntimeStatus.Running)
            assertFalse(state.expectsActiveTunnel)
        }
    }

    @Nested
    inner class Effective {

        @Test
        fun `a fresh interrupted record masks Disconnected as Connecting`() {
            val runtime = VpnRuntimeState(
                desiredRunning = true,
                status = VpnRuntimeStatus.Interrupted,
                heartbeatAtMillis = now - 1_000,
            )
            assertEquals(ConnectionState.Connecting, disconnected.effectiveWith(runtime, now))
        }

        @Test
        fun `a stale interrupted record does not mask Disconnected`() {
            val runtime = VpnRuntimeState(
                desiredRunning = true,
                status = VpnRuntimeStatus.Interrupted,
                heartbeatAtMillis = now - VpnRuntimeState.HEARTBEAT_STALE_AFTER_MS - 1,
            )
            assertEquals(disconnected, disconnected.effectiveWith(runtime, now))
        }

        @Test
        fun `a recorded failure surfaces its reason instead of a bare disconnect`() {
            val runtime = VpnRuntimeState(
                status = VpnRuntimeStatus.Failed,
                lastError = "authentication error, HTTP status code: 401",
            )

            val effective = runtime.let { disconnected.effectiveWith(it, now) }

            assertEquals(
                ConnectionState.Error("authentication error, HTTP status code: 401"),
                effective,
            )
        }

        @Test
        fun `a failure with no recorded reason stays a bare disconnect`() {
            val runtime = VpnRuntimeState(status = VpnRuntimeStatus.Failed, lastError = "  ")
            assertEquals(disconnected, disconnected.effectiveWith(runtime, now))
        }

        @Test
        fun `a live tunnel is never rewritten`() {
            val runtime = VpnRuntimeState(
                status = VpnRuntimeStatus.Failed,
                lastError = "stale failure",
            )
            assertEquals(connected, connected.effectiveWith(runtime, now))
        }

        @Test
        fun `recovery masking wins over a stale failure record`() {
            val runtime = VpnRuntimeState(
                desiredRunning = true,
                status = VpnRuntimeStatus.Running,
                heartbeatAtMillis = now - 1_000,
                lastError = "an older failure",
            )
            assertEquals(ConnectionState.Connecting, disconnected.effectiveWith(runtime, now))
        }
    }
}
