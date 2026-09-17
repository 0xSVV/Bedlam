package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsStore.State.UpdateCheck
import ru.shapovalov.bedlam.feature.update.domain.usecase.FetchUpdateUseCase
import ru.shapovalov.bedlam.feature.update.domain.usecase.ObserveAvailableVersionUseCase
import ru.shapovalov.bedlam.testing.FakePowerReliabilityRepository
import ru.shapovalov.bedlam.testing.FakeQuickSettingsTileRepository
import ru.shapovalov.bedlam.testing.FakeUpdateRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.appUpdate
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.recordLabels
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class SettingsExecutorTest {

    private fun store(
        power: FakePowerReliabilityRepository = FakePowerReliabilityRepository(),
        updates: FakeUpdateRepository = FakeUpdateRepository(),
    ): SettingsStore = SettingsStoreFactory(
        DefaultStoreFactory(),
        power,
        FakeQuickSettingsTileRepository(),
        ObserveAvailableVersionUseCase(updates),
        FetchUpdateUseCase(updates),
    ).create()

    private fun SettingsStore.showBatteryScreen(shown: Boolean, visibilityFirst: Boolean) {
        val foreground = SettingsStore.Intent.SetForeground(shown)
        val visible = SettingsStore.Intent.SetReliabilityVisible(shown)
        if (visibilityFirst) {
            accept(visible)
            accept(foreground)
        } else {
            accept(foreground)
            accept(visible)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `the battery screen polls whichever flag is raised first`(visibilityFirst: Boolean) =
        runTest {
            val power = FakePowerReliabilityRepository()
            store(power).disposeAfter { store ->
                store.showBatteryScreen(shown = true, visibilityFirst = visibilityFirst)
                val shown = power.snapshotReads
                advanceTimeBy(3_000)
                runCurrent()

                assertEquals(shown + 3, power.snapshotReads)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pausing stops polling whichever flag is cleared first`(visibilityFirst: Boolean) =
        runTest {
            val power = FakePowerReliabilityRepository()
            store(power).disposeAfter { store ->
                store.showBatteryScreen(shown = true, visibilityFirst = false)

                store.showBatteryScreen(shown = false, visibilityFirst = visibilityFirst)
                val paused = power.snapshotReads
                advanceTimeBy(5_000)

                assertEquals(paused, power.snapshotReads)
            }
        }

    @Test
    fun `the row follows the newest version the app knows about`() = runTest {
        val updates = FakeUpdateRepository()
        store(updates = updates).disposeAfter { store ->
            assertNull(store.state.availableVersion)

            updates.availableVersion.value = "9.9.9"
            assertEquals("9.9.9", store.state.availableVersion)

            updates.availableVersion.value = null
            assertNull(store.state.availableVersion)
        }
    }

    @Test
    fun `a check that finds nothing newer says the app is up to date`() = runTest {
        val updates = FakeUpdateRepository()
        store(updates = updates).disposeAfter { store ->
            val labels = store.recordLabels()

            store.accept(SettingsStore.Intent.CheckForUpdates)

            assertEquals(UpdateCheck.UpToDate, store.state.updateCheck)
            assertEquals(1, updates.fetches)
            assertEquals(emptyList<SettingsStore.Label>(), labels)
        }
    }

    @Test
    fun `a check that finds an update opens it`() = runTest {
        val updates = FakeUpdateRepository(latest = appUpdate())
        store(updates = updates).disposeAfter { store ->
            val labels = store.recordLabels()

            store.accept(SettingsStore.Intent.CheckForUpdates)

            assertEquals(listOf(SettingsStore.Label.OpenUpdate(appUpdate())), labels)
            assertEquals(UpdateCheck.Idle, store.state.updateCheck)
        }
    }

    @Test
    fun `a failed check says so and the next tap tries again`() = runTest {
        val updates = FakeUpdateRepository()
        updates.fetchError = IOException("HTTP 403")
        store(updates = updates).disposeAfter { store ->
            store.accept(SettingsStore.Intent.CheckForUpdates)
            assertEquals(UpdateCheck.Failed, store.state.updateCheck)

            updates.fetchError = null
            store.accept(SettingsStore.Intent.CheckForUpdates)

            assertEquals(UpdateCheck.UpToDate, store.state.updateCheck)
            assertEquals(2, updates.fetches)
        }
    }

    @Test
    fun `taps during a running check start no second one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val updates = FakeUpdateRepository()
        updates.fetchGate = gate
        store(updates = updates).disposeAfter { store ->
            store.accept(SettingsStore.Intent.CheckForUpdates)
            store.accept(SettingsStore.Intent.CheckForUpdates)

            assertEquals(UpdateCheck.Checking, store.state.updateCheck)
            assertEquals(1, updates.fetches)

            gate.complete(Unit)

            assertEquals(UpdateCheck.UpToDate, store.state.updateCheck)
            assertEquals(1, updates.fetches)
        }
    }
}
