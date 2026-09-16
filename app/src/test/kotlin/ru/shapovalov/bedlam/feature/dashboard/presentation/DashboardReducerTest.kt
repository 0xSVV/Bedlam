package ru.shapovalov.bedlam.feature.dashboard.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.testing.TEST_LINK
import ru.shapovalov.bedlam.testing.reduceAll
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.hysteria.ConnectionState

class DashboardReducerTest {

    private val home = testProfile("a", name = "Home")
    private val work = testProfile("b", name = "Work")
    private val seed = DashboardStore.ImportSheetSeed(TEST_LINK, ProfileImportFormat.Link)

    @Test
    fun `loaded profiles replace the list and the active id`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(profiles = listOf(home), activeProfileId = "a"),
            Msg.ProfilesLoaded(listOf(home, work), "b"),
        )

        assertEquals(DashboardStore.State(profiles = listOf(home, work), activeProfileId = "b"), state)
    }

    @Test
    fun `profiles that disappear lose their latency`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(
                profiles = listOf(home, work),
                latencies = mapOf("a" to LatencyResult.Success(10), "b" to LatencyResult.Success(20)),
            ),
            Msg.ProfilesLoaded(listOf(home), "a"),
        )

        assertEquals(mapOf("a" to LatencyResult.Success(10)), state.latencies)
    }

    @Test
    fun `a latency result for a removed profile is ignored`() {
        val state = DashboardStore.State(profiles = listOf(home))

        assertEquals(
            state,
            DashboardReducer.reduceAll(state, Msg.LatencyUpdated("b", LatencyResult.Success(5))),
        )
    }

    @Test
    fun `a connection change stores the state and its start time`() {
        val connected = testConnected(since = 5_000L)

        val state = DashboardReducer.reduceAll(
            DashboardStore.State(),
            Msg.ConnectionChanged(connected, 5_000L),
        )

        assertEquals(
            DashboardStore.State(connectionState = connected, connectedSinceMillis = 5_000L),
            state,
        )
    }

    @Test
    fun `a failed attempt raises one connection error`() {
        val active = listOf(
            ConnectionState.Connecting,
            testConnected(),
            ConnectionState.Reconnecting(1, "timeout"),
        )
        active.forEach { connection ->
            val failed = DashboardReducer.reduceAll(
                DashboardStore.State(connectionState = connection),
                Msg.ConnectionChanged(ConnectionState.Error("tls"), null),
            )
            assertEquals(DashboardStore.ErrorReason.ConnectionFailed("tls"), failed.error, "$connection")

            val repeated = DashboardReducer.reduceAll(
                failed,
                Msg.ErrorDismissed,
                Msg.ConnectionChanged(ConnectionState.Error("Start failed"), null),
            )
            assertNull(repeated.error, "$connection")
            assertEquals(ConnectionState.Error("Start failed"), repeated.connectionState)
        }
    }

    @Test
    fun `a second error keeps a connection error that was not shown yet`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(connectionState = ConnectionState.Connecting),
            Msg.ConnectionChanged(ConnectionState.Error("tls"), null),
            Msg.ConnectionChanged(ConnectionState.Error("Start failed"), null),
        )

        assertEquals(DashboardStore.ErrorReason.ConnectionFailed("tls"), state.error)
        assertEquals(ConnectionState.Error("Start failed"), state.connectionState)
    }

    @Test
    fun `a failure recorded before the dashboard started raises no connection error`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(),
            Msg.ConnectionChanged(ConnectionState.Error("tls"), null),
        )

        assertEquals(DashboardStore.State(connectionState = ConnectionState.Error("tls")), state)
    }

    @Test
    fun `leaving the error state drops an unshown connection error only`() {
        val unshown = DashboardReducer.reduceAll(
            DashboardStore.State(
                connectionState = ConnectionState.Error("tls"),
                error = DashboardStore.ErrorReason.ConnectionFailed("tls"),
            ),
            Msg.ConnectionChanged(ConnectionState.Connecting, null),
        )
        assertNull(unshown.error)

        val other = DashboardReducer.reduceAll(
            DashboardStore.State(
                connectionState = ConnectionState.Error("tls"),
                error = DashboardStore.ErrorReason.NoActiveProfile,
            ),
            Msg.ConnectionChanged(ConnectionState.Connecting, null),
        )
        assertEquals(DashboardStore.ErrorReason.NoActiveProfile, other.error)
    }

    @Test
    fun `opening the sheet seeds it and clears the last import error`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(importError = "boom"),
            Msg.ImportSheetOpened(seed),
        )

        assertEquals(DashboardStore.State(importSheet = seed), state)
    }

    @Test
    fun `closing the sheet clears it and its error`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(importSheet = seed, importSheetClosing = true, importError = "boom"),
            Msg.ImportSheetClosed,
        )

        assertEquals(DashboardStore.State(), state)
    }

    @Test
    fun `a failed import with the sheet dismissed raises a dashboard error`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(isImporting = true),
            Msg.ImportFailed("boom"),
        )

        assertEquals(DashboardStore.State(error = DashboardStore.ErrorReason.ImportFailed("boom")), state)
    }

    @Test
    fun `a successful import keeps the sheet until it slides away`() {
        val succeeded = DashboardReducer.reduceAll(
            DashboardStore.State(importSheet = seed, isImporting = true),
            Msg.ImportSucceeded,
        )

        assertEquals(DashboardStore.State(importSheet = seed, importSheetClosing = true), succeeded)
    }

    @Test
    fun `a duplicate import slides the sheet away and names the existing profile`() {
        val rejected = DashboardReducer.reduceAll(
            DashboardStore.State(importSheet = seed, isImporting = true),
            Msg.ImportRejectedAsDuplicate("Home"),
        )

        assertEquals(
            DashboardStore.State(
                importSheet = seed,
                importSheetClosing = true,
                error = DashboardStore.ErrorReason.DuplicateProfile("Home"),
            ),
            rejected,
        )
    }

    @Test
    fun `an import that ends after the sheet was dismissed closes nothing`() {
        val importing = DashboardStore.State(isImporting = true)

        assertEquals(DashboardStore.State(), DashboardReducer.reduceAll(importing, Msg.ImportSucceeded))
        assertEquals(
            DashboardStore.State(error = DashboardStore.ErrorReason.DuplicateProfile("Home")),
            DashboardReducer.reduceAll(importing, Msg.ImportRejectedAsDuplicate("Home")),
        )
    }

    @Test
    fun `opening the sheet while it slides away keeps it open`() {
        val other = DashboardStore.ImportSheetSeed("{}", ProfileImportFormat.Json)

        val state = DashboardReducer.reduceAll(
            DashboardStore.State(importSheet = seed, importSheetClosing = true),
            Msg.ImportSheetOpened(other),
        )

        assertEquals(DashboardStore.State(importSheet = other), state)
    }

    @Test
    fun `an import in the open sheet tracks progress and shows its failure there`() {
        val started = DashboardReducer.reduceAll(
            DashboardStore.State(importSheet = seed, importError = "old"),
            Msg.ImportStarted,
        )
        assertEquals(DashboardStore.State(importSheet = seed, isImporting = true), started)

        val failed = DashboardReducer.reduceAll(started, Msg.ImportFailed("boom"))
        assertEquals(DashboardStore.State(importSheet = seed, importError = "boom"), failed)
    }

    @Test
    fun `errors are raised and dismissed`() {
        val raised = DashboardReducer.reduceAll(
            DashboardStore.State(),
            Msg.ErrorRaised(DashboardStore.ErrorReason.NoActiveProfile),
        )
        assertEquals(DashboardStore.State(error = DashboardStore.ErrorReason.NoActiveProfile), raised)

        assertEquals(DashboardStore.State(), DashboardReducer.reduceAll(raised, Msg.ErrorDismissed))
    }

    @Test
    fun `a latency update stores the result for its profile`() {
        val state = DashboardReducer.reduceAll(
            DashboardStore.State(profiles = listOf(home, work)),
            Msg.LatencyUpdated("a", LatencyResult.Measuring),
            Msg.LatencyUpdated("b", LatencyResult.Unreachable),
            Msg.LatencyUpdated("a", LatencyResult.Success(30)),
        )

        assertEquals(
            mapOf("a" to LatencyResult.Success(30), "b" to LatencyResult.Unreachable),
            state.latencies,
        )
    }
}
