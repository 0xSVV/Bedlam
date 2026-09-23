package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.InMemoryPreferencesDataStore

class VpnRuntimeStateRepositoryTest {

    private val repository = VpnRuntimeStateRepository(InMemoryPreferencesDataStore())

    @Test
    fun `the stopped record keeps the id its stop request wrote`() = runTest {
        repository.markStopping(serviceEpoch = 1L, reason = "USER", stopRequestId = "reconnect")
        repository.markStopped("USER")

        val stopped = repository.snapshot()
        assertEquals(VpnRuntimeStatus.Stopped, stopped.status)
        assertEquals("reconnect", stopped.stopRequestId)
    }

    @Test
    fun `a later stop without an id clears the earlier id`() = runTest {
        repository.markStopping(serviceEpoch = 1L, reason = "USER", stopRequestId = "reconnect")
        repository.markStopping(serviceEpoch = 1L, reason = "USER", stopRequestId = null)
        repository.markStopped("USER")
        repository.markStopped("USER")

        assertNull(repository.snapshot().stopRequestId)
    }

    @Test
    fun `a later stop with its own id replaces the earlier id`() = runTest {
        repository.markStopping(serviceEpoch = 1L, reason = "USER", stopRequestId = "first")
        repository.markStopping(serviceEpoch = 1L, reason = "USER", stopRequestId = "second")
        repository.markStopped("USER")
        repository.markStopped("USER")

        assertEquals("second", repository.snapshot().stopRequestId)
    }
}
