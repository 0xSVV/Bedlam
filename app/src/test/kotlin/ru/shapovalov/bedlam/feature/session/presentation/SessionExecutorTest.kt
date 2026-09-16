package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.testing.FakeSessionInfoRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TestBootstrapper
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.sessionInfo
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.hysteria.ConnectionState
import java.io.IOException

@ExtendWith(MainDispatcherExtension::class)
class SessionExecutorTest {

    private fun store(
        repository: FakeSessionInfoRepository,
        bootstrapper: TestBootstrapper<Action>,
    ): Store<SessionStore.Intent, SessionStore.State, Nothing> = DefaultStoreFactory().create(
        initialState = SessionStore.State(),
        bootstrapper = bootstrapper,
        executorFactory = { SessionExecutor(repository) },
        reducer = SessionReducer,
    )

    @Test
    fun `a connected tunnel loads session info once`() = runTest {
        val repository = FakeSessionInfoRepository()
        val tunnel = TestBootstrapper<Action>()
        store(repository, tunnel).disposeAfter { store ->
            tunnel.send(Action.TunnelStateChanged(testConnected()))
            tunnel.send(Action.TunnelStateChanged(testConnected()))

            assertEquals(1, repository.fetchCount)
            assertEquals(sessionInfo(), store.state.info)
            assertFalse(store.state.isLoading)
        }
    }

    @Test
    fun `a refresh or connect while loading does not fetch again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeSessionInfoRepository()
        repository.gate = gate
        val tunnel = TestBootstrapper<Action>()
        store(repository, tunnel).disposeAfter { store ->
            tunnel.send(Action.TunnelStateChanged(testConnected()))
            assertTrue(store.state.isLoading)

            store.accept(SessionStore.Intent.Refresh)
            tunnel.send(Action.TunnelStateChanged(testConnected()))
            assertEquals(1, repository.fetchCount)

            gate.complete(Unit)

            assertEquals(1, repository.fetchCount)
            assertEquals(sessionInfo(), store.state.info)
            assertFalse(store.state.isLoading)
        }
    }

    @Test
    fun `a reconnect marks info stale and the next connect reloads it`() = runTest {
        val repository = FakeSessionInfoRepository()
        val tunnel = TestBootstrapper<Action>()
        store(repository, tunnel).disposeAfter { store ->
            tunnel.send(Action.TunnelStateChanged(testConnected()))
            tunnel.send(Action.TunnelStateChanged(ConnectionState.Reconnecting(1, "timeout")))
            assertTrue(store.state.isStale)

            repository.result = Result.success(sessionInfo(ipv4 = "198.51.100.4"))
            tunnel.send(Action.TunnelStateChanged(testConnected(since = 2_000L)))

            assertEquals(2, repository.fetchCount)
            assertFalse(store.state.isStale)
            assertEquals("198.51.100.4", store.state.info?.ipv4)
        }
    }

    @Test
    fun `refresh while the tunnel is down does nothing`() = runTest {
        val repository = FakeSessionInfoRepository()
        store(repository, TestBootstrapper()).disposeAfter { store ->
            store.accept(SessionStore.Intent.Refresh)

            assertEquals(0, repository.fetchCount)
            assertEquals(SessionStore.State(), store.state)
        }
    }

    @Test
    fun `a failed load reports its message`() = runTest {
        val repository = FakeSessionInfoRepository(Result.failure(IOException("offline")))
        val tunnel = TestBootstrapper<Action>()
        store(repository, tunnel).disposeAfter { store ->
            tunnel.send(Action.TunnelStateChanged(testConnected()))

            assertEquals("offline", store.state.errorMessage)
            assertFalse(store.state.isLoading)
        }
    }
}
