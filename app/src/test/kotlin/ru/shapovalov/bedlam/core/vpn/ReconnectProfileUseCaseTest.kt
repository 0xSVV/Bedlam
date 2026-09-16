package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.FakeProfileRepository
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.hysteria.ConnectionState

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectProfileUseCaseTest {

    private val saved = testProfile("p1", name = "Home", address = "saved.example:443")
    private val clientState = MutableStateFlow<ConnectionState>(testConnected())
    private val runtimeState = MutableStateFlow(
        VpnRuntimeState(desiredRunning = true, status = VpnRuntimeStatus.Running, profileId = "p1"),
    )
    private val events = mutableListOf<String>()
    private var consentRequired = false

    private fun useCase(): ReconnectProfileUseCase = ReconnectProfileUseCase(
        clientState = clientState,
        runtimeState = runtimeState,
        consentRequired = { consentRequired },
        stopTunnel = { events += "stop" },
        startTunnel = { events += "start ${it.config.server.address}" },
        loadProfile = FakeProfileRepository(listOf(saved))::get,
    )

    private fun clientStopped() {
        clientState.value = ConnectionState.Disconnected()
    }

    private fun runtimeStatus(status: VpnRuntimeStatus) {
        runtimeState.update { it.copy(desiredRunning = false, status = status) }
    }

    @Test
    fun `reconnect starts the saved profile only after the tunnel has stopped`() = runTest {
        val reconnect = launch { useCase()("p1") }
        runCurrent()
        assertEquals(listOf("stop"), events)

        clientStopped()
        runtimeStatus(VpnRuntimeStatus.Stopping)
        runCurrent()
        assertEquals(listOf("stop"), events)

        advanceTimeBy(29_999)
        runtimeStatus(VpnRuntimeStatus.Stopped)
        runCurrent()
        assertEquals(listOf("stop", "start saved.example:443"), events)
        assertTrue(reconnect.isCompleted)
    }

    @Test
    fun `reconnect waits for the client as well as the stopped record`() = runTest {
        launch { useCase()("p1") }
        runCurrent()

        runtimeStatus(VpnRuntimeStatus.Stopped)
        runCurrent()
        assertEquals(listOf("stop"), events)

        clientStopped()
        runCurrent()
        assertEquals(listOf("stop", "start saved.example:443"), events)
    }

    @Test
    fun `reconnect gives up when the tunnel does not stop in time`() = runTest {
        val reconnect = launch { useCase()("p1") }
        runCurrent()

        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(reconnect.isCompleted)

        clientStopped()
        runtimeStatus(VpnRuntimeStatus.Stopped)
        runCurrent()
        assertEquals(listOf("stop"), events)
    }

    @Test
    fun `reconnect finishes after its caller is cancelled`() = runTest {
        val reconnect = launch { useCase()("p1") }
        runCurrent()

        reconnect.cancel()
        clientStopped()
        runtimeStatus(VpnRuntimeStatus.Stopped)
        runCurrent()

        assertEquals(listOf("stop", "start saved.example:443"), events)
    }

    @Test
    fun `reconnect leaves a tunnel of another profile alone`() = runTest {
        runtimeState.update { it.copy(profileId = "p2") }

        useCase()("p1")

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `reconnect leaves the tunnel running without VPN consent`() = runTest {
        consentRequired = true

        useCase()("p1")

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `reconnect does nothing without a running tunnel`() = runTest {
        clientStopped()

        useCase()("p1")

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `the tunnel uses a profile only while it runs with that profile`() = runTest {
        val useCase = useCase()
        assertTrue(useCase.isTunnelUsing("p1"))
        assertFalse(useCase.isTunnelUsing("p2"))

        clientStopped()
        assertFalse(useCase.isTunnelUsing("p1"))
    }
}
