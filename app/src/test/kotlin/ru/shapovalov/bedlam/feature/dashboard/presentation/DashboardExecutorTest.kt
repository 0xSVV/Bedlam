package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFailure
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.usecase.ImportProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SetActiveProfileUseCase
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.FakeProfilePinger
import ru.shapovalov.bedlam.testing.FakeProfileRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TEST_LINK
import ru.shapovalov.bedlam.testing.TestBootstrapper
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.recordLabels
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.parseHysteriaUri
import java.io.IOException

@ExtendWith(MainDispatcherExtension::class)
class DashboardExecutorTest {

    private val home = testProfile("a", name = "Home")
    private val work = testProfile("b", name = "Work")
    private val cafe = testProfile("c", name = "Cafe")
    private val seed = DashboardStore.ImportSheetSeed(TEST_LINK, ProfileImportFormat.Link)

    private fun store(
        state: DashboardStore.State,
        repository: FakeProfileRepository = FakeProfileRepository(),
        client: FakeHysteriaClient = FakeHysteriaClient(),
        pinger: FakeProfilePinger = FakeProfilePinger(),
        bootstrapper: TestBootstrapper<Action>? = null,
        switchProfile: suspend (String) -> Unit = {},
    ): Store<DashboardStore.Intent, DashboardStore.State, DashboardStore.Label> =
        DefaultStoreFactory().create(
            initialState = state,
            bootstrapper = bootstrapper,
            executorFactory = {
                DashboardExecutor(
                    SetActiveProfileUseCase(repository),
                    ImportProfileUseCase(repository, client),
                    pinger::ping,
                    switchProfile,
                )
            },
            reducer = DashboardReducer,
        )

    private fun activeTunnelStates() = listOf(
        ConnectionState.Connecting,
        testConnected(),
        ConnectionState.Reconnecting(1, "timeout"),
    )

    private fun pendingSwitchState(connection: ConnectionState = testConnected()) =
        DashboardStore.State(
            profiles = listOf(home, work, cafe),
            activeProfileId = "a",
            pendingSwitchProfileId = "b",
            connectionState = connection,
        )

    @Test
    fun `toggle while disconnected with an active profile requests a start`() = runTest {
        val state = DashboardStore.State(profiles = listOf(home), activeProfileId = "a")
        store(state).disposeAfter { store ->
            val labels = store.recordLabels()

            store.accept(DashboardStore.Intent.ToggleConnection)

            assertEquals(listOf(DashboardStore.Label.RequestStartVpn(home)), labels)
            assertNull(store.state.error)
        }
    }

    @Test
    fun `toggle while the tunnel is active requests a stop`() = runTest {
        activeTunnelStates().forEach { connection ->
            val state = DashboardStore.State(
                profiles = listOf(home),
                activeProfileId = "a",
                connectionState = connection,
            )
            store(state).disposeAfter { store ->
                val labels = store.recordLabels()

                store.accept(DashboardStore.Intent.ToggleConnection)

                assertEquals(listOf(DashboardStore.Label.RequestStopVpn), labels, "$connection")
            }
        }
    }

    @Test
    fun `toggle without an active profile raises an error and requests nothing`() = runTest {
        store(DashboardStore.State(profiles = listOf(home))).disposeAfter { store ->
            val labels = store.recordLabels()

            store.accept(DashboardStore.Intent.ToggleConnection)

            assertEquals(emptyList<DashboardStore.Label>(), labels)
            assertEquals(DashboardStore.ErrorReason.NoActiveProfile, store.state.error)

            store.accept(DashboardStore.Intent.DismissError)

            assertNull(store.state.error)
        }
    }

    @Test
    fun `selecting a profile makes it active`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work), activeId = "a")
        val state = DashboardStore.State(profiles = listOf(home, work), activeProfileId = "a")
        store(state, repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.SelectProfile("b"))

            assertEquals("b", repository.active.value)
            assertNull(store.state.pendingSwitchProfileId)
        }
    }

    @Test
    fun `selecting another profile while the tunnel is active asks for confirmation`() = runTest {
        activeTunnelStates().forEach { connection ->
            val repository = FakeProfileRepository(listOf(home, work), activeId = "a")
            val state = DashboardStore.State(
                profiles = listOf(home, work),
                activeProfileId = "a",
                connectionState = connection,
            )
            store(state, repository).disposeAfter { store ->
                store.accept(DashboardStore.Intent.SelectProfile("b"))

                assertEquals("b", store.state.pendingSwitchProfileId, "$connection")
                assertEquals(work, store.state.pendingSwitchProfile, "$connection")
                assertEquals("a", repository.active.value, "$connection")
            }
        }
    }

    @Test
    fun `confirming the switch activates the profile and restarts the tunnel with it`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work, cafe), activeId = "a")
        val switches = mutableListOf<String>()
        val switchProfile: suspend (String) -> Unit = { id ->
            switches += "$id while ${repository.active.value} is active"
        }
        store(pendingSwitchState(), repository, switchProfile = switchProfile).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ConfirmSwitch)

            assertNull(store.state.pendingSwitchProfileId)
            assertEquals("b", repository.active.value)
            assertEquals(listOf("b while b is active"), switches)
        }
    }

    @Test
    fun `cancelling the switch keeps the current profile`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work, cafe), activeId = "a")
        val switches = mutableListOf<String>()
        store(pendingSwitchState(), repository, switchProfile = { switches += it }).disposeAfter { store ->
            store.accept(DashboardStore.Intent.CancelSwitch)

            assertNull(store.state.pendingSwitchProfileId)
            assertEquals("a", repository.active.value)
            assertEquals(emptyList<String>(), switches)
        }
    }

    @Test
    fun `selecting the active profile cancels a pending switch`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work, cafe), activeId = "a")
        store(pendingSwitchState(), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.SelectProfile("a"))

            assertNull(store.state.pendingSwitchProfileId)
            assertEquals("a", repository.active.value)
        }
    }

    @Test
    fun `selecting a third profile moves the pending switch`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work, cafe), activeId = "a")
        store(pendingSwitchState(), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.SelectProfile("c"))

            assertEquals("c", store.state.pendingSwitchProfileId)
            assertEquals("a", repository.active.value)
        }
    }

    @Test
    fun `a pending switch is applied without a restart when the tunnel stops`() = runTest {
        listOf(ConnectionState.Disconnected(), ConnectionState.Error("tls")).forEach { stopped ->
            val repository = FakeProfileRepository(listOf(home, work, cafe), activeId = "a")
            val switches = mutableListOf<String>()
            val bootstrapper = TestBootstrapper<Action>()
            val store = store(
                pendingSwitchState(),
                repository,
                bootstrapper = bootstrapper,
                switchProfile = { switches += it },
            )
            store.disposeAfter {
                bootstrapper.send(Action.ConnectionStateChanged(stopped, null))

                assertNull(store.state.pendingSwitchProfileId, "$stopped")
                assertEquals("b", repository.active.value, "$stopped")
                assertEquals(emptyList<String>(), switches, "$stopped")
            }
        }
    }

    @Test
    fun `a pending switch survives a tunnel that keeps reconnecting`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work, cafe), activeId = "a")
        val bootstrapper = TestBootstrapper<Action>()
        store(pendingSwitchState(), repository, bootstrapper = bootstrapper).disposeAfter { store ->
            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Reconnecting(2, "lost"), null))
            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Connecting, null))

            assertEquals("b", store.state.pendingSwitchProfileId)
            assertEquals("a", repository.active.value)
        }
    }

    @Test
    fun `confirming without a pending switch does nothing`() = runTest {
        val repository = FakeProfileRepository(listOf(home, work), activeId = "a")
        val switches = mutableListOf<String>()
        val state = DashboardStore.State(
            profiles = listOf(home, work),
            activeProfileId = "a",
            connectionState = testConnected(),
        )
        store(state, repository, switchProfile = { switches += it }).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ConfirmSwitch)

            assertEquals("a", repository.active.value)
            assertEquals(emptyList<String>(), switches)
        }
    }

    @Test
    fun `opening the import sheet seeds it with the trimmed text and its format`() = runTest {
        store(DashboardStore.State()).disposeAfter { store ->
            store.accept(DashboardStore.Intent.OpenImport("  $TEST_LINK\n"))

            assertEquals(seed, store.state.importSheet)
        }
    }

    @Test
    fun `importing a link saves it and activates the first profile`() = runTest {
        val repository = FakeProfileRepository()
        store(DashboardStore.State(importSheet = seed), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, " "))

            val saved = repository.profiles.value.single()
            assertEquals("Imported", saved.name)
            assertEquals(saved.id, repository.active.value)
            assertFalse(store.state.isImporting)
            assertNull(store.state.importError)
        }
    }

    @Test
    fun `importing keeps an existing active profile`() = runTest {
        val repository = FakeProfileRepository(listOf(home), activeId = "a")
        val state = DashboardStore.State(
            profiles = listOf(home),
            activeProfileId = "a",
            importSheet = seed,
        )
        store(state, repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, "Office"))

            assertEquals(listOf("Home", "Office"), repository.profiles.value.map { it.name })
            assertEquals("a", repository.active.value)
        }
    }

    @Test
    fun `a successful import slides the sheet away before clearing it`() = runTest {
        store(DashboardStore.State(importSheet = seed)).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))

            assertEquals(seed, store.state.importSheet)
            assertTrue(store.state.importSheetClosing)
            assertFalse(store.state.isImporting)
            assertNull(store.state.error)

            store.accept(DashboardStore.Intent.CloseImport)

            assertNull(store.state.importSheet)
            assertFalse(store.state.importSheetClosing)
        }
    }

    @Test
    fun `a duplicate import slides the sheet away and names the existing profile`() = runTest {
        val existing = home.copy(config = parseHysteriaUri(TEST_LINK).config)
        val repository = FakeProfileRepository(listOf(existing), activeId = "a")
        store(DashboardStore.State(importSheet = seed), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))

            assertEquals(listOf(existing), repository.profiles.value)
            assertEquals(seed, store.state.importSheet)
            assertTrue(store.state.importSheetClosing)
            assertNull(store.state.importError)
            assertEquals(DashboardStore.ErrorReason.DuplicateProfile("Home"), store.state.error)
        }
    }

    @Test
    fun `importing several links saves each, activates the first and reports the count`() = runTest {
        val repository = FakeProfileRepository()
        val text = "$ONE_LINK\n$TWO_LINK"
        val state = DashboardStore.State(importSheet = DashboardStore.ImportSheetSeed(text, ProfileImportFormat.Link))
        store(state, repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, text, "Ignored"))

            assertEquals(listOf("One", "Two"), repository.profiles.value.map { it.name })
            assertEquals(repository.profiles.value.first().id, repository.active.value)
            assertTrue(store.state.importSheetClosing)
            assertFalse(store.state.isImporting)
            assertEquals(DashboardStore.ErrorReason.ProfilesImported(2, 2, emptyList()), store.state.error)
        }
    }

    @Test
    fun `a paste with a failing link keeps the others and names the failure`() = runTest {
        val repository = FakeProfileRepository()
        val text = "$ONE_LINK hysteria2://x@bad.example:0/#Bad"
        val state = DashboardStore.State(importSheet = DashboardStore.ImportSheetSeed(text, ProfileImportFormat.Link))
        store(state, repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, text, ""))

            assertEquals(listOf("One"), repository.profiles.value.map { it.name })
            assertTrue(store.state.importSheetClosing)
            assertEquals(
                DashboardStore.ErrorReason.ProfilesImported(
                    imported = 1,
                    total = 2,
                    failures = listOf(
                        ProfileImportFailure.Invalid(2, "Port 0 in the link is not between 1 and 65535"),
                    ),
                ),
                store.state.error,
            )
        }
    }

    @Test
    fun `a paste where every link fails stays in the sheet with each failure`() = runTest {
        val existing = home.copy(config = parseHysteriaUri(ONE_LINK).config)
        val repository = FakeProfileRepository(listOf(existing), activeId = "a")
        val text = "$ONE_LINK\nhysteria2://x@bad.example:0/"
        val seed = DashboardStore.ImportSheetSeed(text, ProfileImportFormat.Link)
        store(DashboardStore.State(importSheet = seed), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, text, ""))

            assertEquals(
                DashboardStore.State(
                    importSheet = seed,
                    importFailures = listOf(
                        ProfileImportFailure.Duplicate(1, "Home"),
                        ProfileImportFailure.Invalid(2, "Port 0 in the link is not between 1 and 65535"),
                    ),
                ),
                store.state,
            )
        }
    }

    @Test
    fun `a paste that fails after the sheet was dismissed is reported on the dashboard`() = runTest {
        val text = "hysteria2://x@bad.example:0/\nhysteria2://y@bad.example:0/"
        store(DashboardStore.State()).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, text, ""))

            val failure = "Port 0 in the link is not between 1 and 65535"
            assertEquals(
                DashboardStore.ErrorReason.ProfilesImported(
                    imported = 0,
                    total = 2,
                    failures = listOf(
                        ProfileImportFailure.Invalid(1, failure),
                        ProfileImportFailure.Invalid(2, failure),
                    ),
                ),
                store.state.error,
            )
        }
    }

    @Test
    fun `retrying in the sheet clears the previous failures`() = runTest {
        val failures = listOf(ProfileImportFailure.Invalid(1, "bad"))
        val state = DashboardStore.State(importSheet = seed, importFailures = failures)
        store(state).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))

            assertEquals(emptyList<ProfileImportFailure>(), store.state.importFailures)
        }
    }

    @Test
    fun `blank import text and a second import while importing are ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeProfileRepository()
        repository.observeAllGate = gate
        val client = FakeHysteriaClient()
        store(DashboardStore.State(importSheet = seed), repository, client).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, "  ", ""))

            assertFalse(store.state.isImporting)
            assertEquals(0, client.validateCalls)

            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))

            assertTrue(store.state.isImporting)
            assertEquals(1, client.validateCalls)

            gate.complete(Unit)

            assertEquals(1, repository.profiles.value.size)
            assertFalse(store.state.isImporting)
        }
    }

    @Test
    fun `an import that fails after the sheet was dismissed is reported on the dashboard`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeProfileRepository()
        repository.observeAllGate = gate
        repository.upsertFailure = IOException("disk full")
        store(DashboardStore.State(), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.OpenImport(TEST_LINK))
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))
            store.accept(DashboardStore.Intent.CloseImport)

            gate.complete(Unit)

            assertEquals(
                DashboardStore.State(error = DashboardStore.ErrorReason.ImportFailed("disk full")),
                store.state,
            )
        }
    }

    @Test
    fun `an import that fails in the open sheet is reported in the sheet`() = runTest {
        val repository = FakeProfileRepository()
        repository.upsertFailure = IOException("disk full")
        store(DashboardStore.State(importSheet = seed), repository).disposeAfter { store ->
            store.accept(DashboardStore.Intent.ImportProfile(ProfileImportFormat.Link, TEST_LINK, ""))

            assertEquals(
                DashboardStore.State(importSheet = seed, importError = "disk full"),
                store.state,
            )
        }
    }

    @Test
    fun `the connection error is raised once per entry into the error state`() = runTest {
        val bootstrapper = TestBootstrapper<Action>()
        val state = DashboardStore.State(connectionState = ConnectionState.Connecting)
        store(state, bootstrapper = bootstrapper).disposeAfter { store ->
            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Error("tls"), null))

            assertEquals(DashboardStore.ErrorReason.ConnectionFailed("tls"), store.state.error)

            store.accept(DashboardStore.Intent.DismissError)
            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Error("tls"), null))
            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Error("tls"), null))

            assertNull(store.state.error)

            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Connecting, null))
            bootstrapper.send(Action.ConnectionStateChanged(ConnectionState.Error("tls"), null))

            assertEquals(DashboardStore.ErrorReason.ConnectionFailed("tls"), store.state.error)
            assertEquals(ConnectionState.Error("tls"), store.state.connectionState)
        }
    }

    @Test
    fun `a connected tunnel pings the active profile`() = runTest {
        val pinger = FakeProfilePinger()
        val bootstrapper = TestBootstrapper<Action>()
        val state = DashboardStore.State(profiles = listOf(home, work), activeProfileId = "a")
        store(state, pinger = pinger, bootstrapper = bootstrapper).disposeAfter { store ->
            bootstrapper.send(Action.TunnelConnected)

            assertEquals(listOf("a"), pinger.calls.map { it.first })
            assertEquals(mapOf("a" to LatencyResult.Measuring), store.state.latencies)

            pinger.calls.single().second.complete(LatencyResult.Success(42))

            assertEquals(mapOf("a" to LatencyResult.Success(42)), store.state.latencies)
        }
    }

    @Test
    fun `a repeated ping of a profile reports only the latest measurement`() = runTest {
        val pinger = FakeProfilePinger()
        store(DashboardStore.State(profiles = listOf(home)), pinger = pinger).disposeAfter { store ->
            store.accept(DashboardStore.Intent.PingProfile("a"))
            store.accept(DashboardStore.Intent.PingProfile("a"))
            store.accept(DashboardStore.Intent.PingProfile("a"))

            assertEquals(1, pinger.active)

            pinger.calls[0].second.complete(LatencyResult.Success(900))
            pinger.calls[1].second.complete(LatencyResult.Unreachable)

            assertEquals(LatencyResult.Measuring, store.state.latencies["a"])

            pinger.calls[2].second.complete(LatencyResult.Success(40))

            assertEquals(LatencyResult.Success(40), store.state.latencies["a"])
            assertEquals(0, pinger.active)
        }
    }

    @Test
    fun `a profile deleted during its ping gets no latency`() = runTest {
        val pinger = FakeProfilePinger()
        val bootstrapper = TestBootstrapper<Action>()
        val state = DashboardStore.State(profiles = listOf(home, work))
        store(state, pinger = pinger, bootstrapper = bootstrapper).disposeAfter { store ->
            store.accept(DashboardStore.Intent.PingAllProfiles)
            bootstrapper.send(Action.ProfilesLoaded(listOf(home), null))

            assertEquals(1, pinger.active)
            assertEquals(mapOf("a" to LatencyResult.Measuring), store.state.latencies)

            pinger.calls.forEach { it.second.complete(LatencyResult.Success(5)) }

            assertEquals(mapOf("a" to LatencyResult.Success(5)), store.state.latencies)
        }
    }

    @Test
    fun `ping all keeps one measurement per profile`() = runTest {
        val pinger = FakeProfilePinger()
        val state = DashboardStore.State(profiles = listOf(home, work))
        store(state, pinger = pinger).disposeAfter { store ->
            store.accept(DashboardStore.Intent.PingProfile("a"))
            store.accept(DashboardStore.Intent.PingAllProfiles)
            store.accept(DashboardStore.Intent.PingAllProfiles)

            assertEquals(listOf("a", "a", "b", "a", "b"), pinger.calls.map { it.first })
            assertEquals(2, pinger.active)

            pinger.calls.take(3).forEach { it.second.complete(LatencyResult.Success(900)) }

            assertEquals(
                mapOf("a" to LatencyResult.Measuring, "b" to LatencyResult.Measuring),
                store.state.latencies,
            )

            pinger.calls[3].second.complete(LatencyResult.Success(30))
            pinger.calls[4].second.complete(LatencyResult.Unreachable)

            assertEquals(
                mapOf("a" to LatencyResult.Success(30), "b" to LatencyResult.Unreachable),
                store.state.latencies,
            )
        }
    }
}

private const val ONE_LINK = "hysteria2://a@one.example:443/#One"
private const val TWO_LINK = "hysteria2://b@two.example:443/#Two"
