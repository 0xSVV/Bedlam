package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent.Child
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TestGraph
import ru.shapovalov.bedlam.testing.withComponentContext

@ExtendWith(MainDispatcherExtension::class)
class SettingsComponentTest {

    private fun SettingsComponent.activeChild(): Child =
        childStack.value.active.instance

    @Test
    fun `opening app selection routing and battery pushes each screen`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val settings = graph.settingsFactory.create(context)
            lifecycle.resume()
            assertEquals(Child.Root, settings.activeChild())

            settings.onOpenAppSelection()
            assertInstanceOf(Child.AppSelection::class.java, settings.activeChild())

            settings.onOpenRouting()
            assertInstanceOf(Child.Routing::class.java, settings.activeChild())

            settings.onOpenBatteryReliability()
            assertEquals(Child.BatteryReliability, settings.activeChild())
            assertEquals(4, settings.childStack.value.items.size)
        }
    }

    @Test
    fun `back returns to the settings root`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val settings = graph.settingsFactory.create(context)
            lifecycle.resume()
            settings.onOpenRouting()

            settings.onBack()

            assertEquals(Child.Root, settings.activeChild())
            assertEquals(1, settings.childStack.value.items.size)
        }
    }

    @Test
    fun `nested screens close through their own back callbacks`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val settings = graph.settingsFactory.create(context)
            lifecycle.resume()

            settings.onOpenAppSelection()
            assertInstanceOf(Child.AppSelection::class.java, settings.activeChild())
                .component
                .onBackPressed()
            assertEquals(Child.Root, settings.activeChild())

            settings.onOpenRouting()
            assertInstanceOf(Child.Routing::class.java, settings.activeChild())
                .component
                .onBackPressed()
            assertEquals(Child.Root, settings.activeChild())
        }
    }

    @Test
    fun `marking reliability confirmed stores the fingerprint`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val settings = graph.settingsFactory.create(context)
            lifecycle.resume()

            settings.onMarkReliabilityConfirmed("fp")

            assertEquals(listOf("fp"), graph.power.confirmedFingerprints)
            assertEquals("fp", settings.state.value.confirmedReliabilityFingerprint)
        }
    }

    @Test
    fun `switching the tile flag writes and reflects it`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val settings = graph.settingsFactory.create(context)
            lifecycle.resume()

            settings.onSetQuickSettingsTileAdded(true)

            assertTrue(graph.tile.added.value)
            assertTrue(settings.state.value.quickSettingsTileAdded)
        }
    }
}
