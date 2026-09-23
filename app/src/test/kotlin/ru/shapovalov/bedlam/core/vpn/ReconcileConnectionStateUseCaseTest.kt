package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.InMemoryPreferencesDataStore
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.hysteria.ConnectionState

class ReconcileConnectionStateUseCaseTest {

    private val client = FakeHysteriaClient()
    private val runtime = VpnRuntimeStateRepository(InMemoryPreferencesDataStore())
    private var serviceRunning = false
    private var consentRequired = false
    private var activeProfileSaved = true
    private var starts = 0

    private val reconcile = ReconcileConnectionStateUseCase(
        client = client,
        isServiceRunning = { serviceRunning },
        consentRequired = { consentRequired },
        startActiveProfile = {
            if (activeProfileSaved) {
                starts++
                StartActiveProfileResult.Started
            } else {
                StartActiveProfileResult.NoActiveProfile
            }
        },
        runtimeStateRepository = runtime,
    )

    private suspend fun tunnelWasRunning() {
        runtime.markStarting(serviceEpoch = 3L, profileId = "p1", profileName = "Home")
        runtime.heartbeat(serviceEpoch = 3L, clientState = testConnected())
    }

    @Test
    fun `a tunnel that was running when the process died is started again`() = runTest {
        tunnelWasRunning()

        assertEquals(ReconcileResult.Restarted, reconcile())
        assertEquals(1, starts)
    }

    @Test
    fun `an interrupted tunnel is started again`() = runTest {
        tunnelWasRunning()
        runtime.markInterrupted(serviceEpoch = 3L, reason = "Service destroyed")

        assertEquals(ReconcileResult.Restarted, reconcile())
        assertEquals(1, starts)
    }

    @Test
    fun `a tunnel that stopped on its own is not started again`() = runTest {
        tunnelWasRunning()
        runtime.markFailed("DNS reapply after network change failed")

        assertEquals(ReconcileResult.Unchanged, reconcile())
        assertEquals(0, starts)
    }

    @Test
    fun `a tunnel the user stopped is not started again`() = runTest {
        tunnelWasRunning()
        runtime.markStopping(serviceEpoch = 3L, reason = "USER", stopRequestId = null)
        runtime.markStopped("USER")

        assertEquals(ReconcileResult.Unchanged, reconcile())
        assertEquals(0, starts)
    }

    @Test
    fun `a live tunnel is left alone`() = runTest {
        tunnelWasRunning()
        client.connectionState.value = testConnected()
        serviceRunning = true

        assertEquals(ReconcileResult.Unchanged, reconcile())
        assertEquals(0, starts)
    }

    @Test
    fun `a restore without VPN permission fails with the reason`() = runTest {
        tunnelWasRunning()
        consentRequired = true

        assertEquals(ReconcileResult.Failed("VPN permission is required"), reconcile())
        assertEquals(0, starts)
        assertEquals(
            ConnectionState.Error("VPN permission is required"),
            ConnectionState.Disconnected().effectiveWith(runtime.snapshot()),
        )
    }

    @Test
    fun `a restore without an active profile fails with the reason`() = runTest {
        tunnelWasRunning()
        activeProfileSaved = false

        assertEquals(ReconcileResult.Failed("No active profile"), reconcile())
        assertEquals(
            ConnectionState.Error("No active profile"),
            ConnectionState.Disconnected().effectiveWith(runtime.snapshot()),
        )
    }
}
