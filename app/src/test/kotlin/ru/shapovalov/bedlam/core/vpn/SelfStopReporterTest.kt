package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.log.AppLog
import ru.shapovalov.bedlam.testing.InMemoryPreferencesDataStore
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

class SelfStopReporterTest {

    private val appLog = AppLog()
    private val runtime = VpnRuntimeStateRepository(InMemoryPreferencesDataStore())
    private val alerts = mutableListOf<String>()
    private val reporter = SelfStopReporter(appLog, runtime) { alerts += it }

    private suspend fun runningTunnel() {
        runtime.markStarting(serviceEpoch = 7L, profileId = "p1", profileName = "Home")
        runtime.heartbeat(serviceEpoch = 7L, clientState = testConnected())
    }

    private fun logLines(): List<Pair<LogLevel, String>> =
        appLog.flow.replayCache.map { it.level to it.message }

    private suspend fun shownAfterStop(): ConnectionState {
        val state = runtime.snapshot()
        return ConnectionState.Disconnected(DisconnectReason.USER)
            .effectiveWith(state, nowMillis = state.heartbeatAtMillis)
    }

    @Test
    fun `an interruption shows as failed with its reason instead of Connecting`() = runTest {
        runningTunnel()

        reporter.report(SelfStop.Interruption("DNS reapply after network change failed"))

        val state = runtime.snapshot()
        assertEquals(VpnRuntimeStatus.Failed, state.status)
        assertFalse(state.expectsActiveTunnel)
        assertEquals(
            ConnectionState.Error("DNS reapply after network change failed"),
            shownAfterStop(),
        )
    }

    @Test
    fun `an interruption alerts and logs a warning`() = runTest {
        runningTunnel()

        reporter.report(SelfStop.Interruption("DNS reapply after network change failed"))

        assertEquals(listOf("DNS reapply after network change failed"), alerts)
        assertEquals(
            listOf(LogLevel.WARN to "Tunnel interrupted: DNS reapply after network change failed"),
            logLines(),
        )
    }

    @Test
    fun `a terminal failure alerts with its reason and logs an error`() = runTest {
        runningTunnel()

        reporter.report(SelfStop.Failure("authentication error, HTTP status code: 401"))

        assertEquals(listOf("authentication error, HTTP status code: 401"), alerts)
        assertEquals(
            listOf(LogLevel.ERROR to "Tunnel failed: authentication error, HTTP status code: 401"),
            logLines(),
        )
        assertEquals(
            ConnectionState.Error("authentication error, HTTP status code: 401"),
            shownAfterStop(),
        )
    }

    @Test
    fun `a failure without a message still shows as failed`() = runTest {
        runningTunnel()

        reporter.report(SelfStop.Failure(" "))

        assertEquals(listOf("The tunnel failed"), alerts)
        assertEquals(ConnectionState.Error("The tunnel failed"), shownAfterStop())
    }

    @Test
    fun `a failed reapply shows a plain reason and logs the exception, not a disconnect`() =
        runTest {
            runningTunnel()

            reporter.report(
                SelfStop.ReapplyFailure(
                    IllegalStateException("VpnService.establish() returned null")
                )
            )

            val reason = "Could not apply the changed settings"
            assertEquals(listOf(reason), alerts)
            assertEquals(
                listOf(
                    LogLevel.ERROR to "Tunnel failed: $reason: " +
                        "IllegalStateException: VpnService.establish() returned null"
                ),
                logLines(),
            )
            assertEquals(ConnectionState.Error(reason), shownAfterStop())
        }

    @Test
    fun `a failed start without a message shows a plain reason and logs the exception`() =
        runTest {
            reporter.report(SelfStop.StartupFailure(IllegalStateException()))

            assertEquals(listOf("Could not start the VPN"), alerts)
            assertEquals(
                listOf(
                    LogLevel.ERROR to "Tunnel failed: Could not start the VPN: IllegalStateException"
                ),
                logLines(),
            )
            assertEquals(ConnectionState.Error("Could not start the VPN"), shownAfterStop())
        }

    @Test
    fun `a failed start keeps the handshake error`() = runTest {
        reporter.report(SelfStop.StartupFailure(Exception("tls: handshake failure")))

        assertEquals(listOf("tls: handshake failure"), alerts)
        assertEquals(
            listOf(
                LogLevel.ERROR to
                    "Tunnel failed: Could not start the VPN: Exception: tls: handshake failure"
            ),
            logLines(),
        )
    }

    @Test
    fun `a start without an active profile alerts`() = runTest {
        reporter.report(SelfStop.NoActiveProfile)

        assertEquals(listOf("No active profile"), alerts)
        assertEquals(
            listOf(LogLevel.ERROR to "Could not start the tunnel: No active profile"),
            logLines(),
        )
        assertEquals(ConnectionState.Error("No active profile"), shownAfterStop())
    }

    @Test
    fun `a start with an unreadable config alerts`() = runTest {
        reporter.report(SelfStop.InvalidConfig)

        assertEquals(listOf("The profile config could not be read"), alerts)
        assertEquals(
            ConnectionState.Error("The profile config could not be read"),
            shownAfterStop(),
        )
    }

    @Test
    fun `a start Android refuses alerts instead of waiting to reconnect`() = runTest {
        runningTunnel()

        reporter.report(SelfStop.ForegroundRefused)

        assertEquals(listOf("Android refused to start the VPN service"), alerts)
        assertFalse(runtime.snapshot().expectsActiveTunnel)
    }

    @Test
    fun `process death still waits for recovery`() = runTest {
        runningTunnel()

        runtime.markInterrupted(serviceEpoch = 7L, reason = "Service destroyed")

        assertEquals(ConnectionState.Connecting, shownAfterStop())
    }
}
