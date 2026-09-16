package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.shapovalov.bedlam.testing.FakePowerReliabilityRepository
import ru.shapovalov.bedlam.testing.FakeQuickSettingsTileRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.disposeAfter

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainDispatcherExtension::class)
class SettingsExecutorTest {

    private fun store(power: FakePowerReliabilityRepository): SettingsStore =
        SettingsStoreFactory(DefaultStoreFactory(), power, FakeQuickSettingsTileRepository())
            .create()

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
}
