package ru.shapovalov.bedlam.feature.session.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.reduceAll
import ru.shapovalov.bedlam.testing.sessionInfo

class SessionReducerTest {

    private val info = sessionInfo()

    @Test
    fun `loading messages track the request`() {
        val failed = SessionStore.State(tunnelUp = true, errorMessage = "old")

        val started = SessionReducer.reduceAll(failed, Msg.LoadingStarted)
        assertEquals(SessionStore.State(tunnelUp = true, isLoading = true), started)

        val succeeded = SessionReducer.reduceAll(
            started.copy(isStale = true),
            Msg.LoadingSucceeded(info),
        )
        assertEquals(SessionStore.State(info = info, tunnelUp = true), succeeded)

        val retried = SessionReducer.reduceAll(
            succeeded,
            Msg.LoadingStarted,
            Msg.LoadingFailed("offline"),
        )
        assertEquals(
            SessionStore.State(info = info, tunnelUp = true, errorMessage = "offline"),
            retried,
        )
    }

    @Test
    fun `tunnel messages mark up stale and down`() {
        assertEquals(
            SessionStore.State(tunnelUp = true),
            SessionReducer.reduceAll(SessionStore.State(), Msg.TunnelUp),
        )

        assertEquals(
            SessionStore.State(),
            SessionReducer.reduceAll(
                SessionStore.State(tunnelUp = true, isLoading = true),
                Msg.TunnelWobbling,
            ),
        )

        assertEquals(
            SessionStore.State(info = info, isStale = true),
            SessionReducer.reduceAll(
                SessionStore.State(info = info, tunnelUp = true, isLoading = true),
                Msg.TunnelWobbling,
            ),
        )

        val stale = SessionStore.State(
            info = info,
            tunnelUp = true,
            isStale = true,
            errorMessage = "x",
        )
        assertEquals(SessionStore.State(), SessionReducer.reduceAll(stale, Msg.TunnelDown))
    }
}
