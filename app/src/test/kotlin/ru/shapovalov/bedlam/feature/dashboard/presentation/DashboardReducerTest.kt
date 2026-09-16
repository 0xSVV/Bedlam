package ru.shapovalov.bedlam.feature.dashboard.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.testing.TEST_LINK
import ru.shapovalov.bedlam.testing.reduceAll
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.bedlam.testing.testProfile

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
            DashboardStore.State(importSheet = seed, importError = "boom"),
            Msg.ImportSheetClosed,
        )

        assertEquals(DashboardStore.State(), state)
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
