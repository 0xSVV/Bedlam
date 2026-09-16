package ru.shapovalov.bedlam.core.vpn

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val mutex = Mutex()
        private val current = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = current

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = mutex.withLock {
            transform(current.value).also { current.value = it }
        }
    }
}
