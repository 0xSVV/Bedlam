package ru.shapovalov.bedlam.feature.dashboard.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent.Child
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent.OnStartVpn
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent.OnStopVpn
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TEST_LINK
import ru.shapovalov.bedlam.testing.TestGraph
import ru.shapovalov.bedlam.testing.testConnected
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.bedlam.testing.withComponentContext

@ExtendWith(MainDispatcherExtension::class)
class DashboardContainerComponentTest {

    private val importSeed = DashboardStore.ImportSheetSeed(TEST_LINK, ProfileImportFormat.Link)

    private fun TestGraph.container(
        context: ComponentContext,
        onStartVpn: OnStartVpn = OnStartVpn {},
        onStopVpn: OnStopVpn = OnStopVpn {},
    ): DashboardContainerComponent =
        dashboardContainerFactory.create(context, onStartVpn, onStopVpn)

    private fun DashboardContainerComponent.activeChild(): Child =
        childStack.value.active.instance

    private fun DashboardContainerComponent.home(): DashboardComponent =
        assertInstanceOf(Child.Root::class.java, childStack.value.items.first().instance).component

    @Test
    fun `opening the session pushes it over the dashboard`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val container = graph.container(context)
            lifecycle.resume()

            container.home().onOpenSession()

            assertInstanceOf(Child.Session::class.java, container.activeChild())
            assertEquals(2, container.childStack.value.items.size)
        }
    }

    @Test
    fun `session back returns to the dashboard root`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val container = graph.container(context)
            lifecycle.resume()
            container.home().onOpenSession()

            assertInstanceOf(Child.Session::class.java, container.activeChild())
                .component
                .onBackPressed()

            assertInstanceOf(Child.Root::class.java, container.activeChild())
            assertEquals(1, container.childStack.value.items.size)
        }
    }

    @Test
    fun `opening a profile pushes its editor for that profile`() = runTest {
        val graph = TestGraph()
        val profile = testProfile("p1", name = "Home")
        graph.profiles.profiles.value = listOf(profile, testProfile("p2"))
        withComponentContext { lifecycle, context ->
            val container = graph.container(context)
            lifecycle.resume()

            container.home().onOpenProfileConfig("p1")

            val editor = assertInstanceOf(
                Child.ProfileConfig::class.java,
                container.activeChild(),
            ).component
            assertEquals("p1", editor.state.value.profileId)
            assertEquals(profile, editor.state.value.original)
        }
    }

    @Test
    fun `an import link pops nested screens and opens the import sheet`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val container = graph.container(context)
            lifecycle.resume()
            container.home().onOpenSession()

            container.onImportLink(TEST_LINK)

            assertEquals(1, container.childStack.value.items.size)
            assertInstanceOf(Child.Root::class.java, container.activeChild())
            assertEquals(importSeed, container.home().state.value.importSheet)
        }
    }

    @Test
    fun `the dashboard keeps its component and state under a nested screen`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val container = graph.container(context)
            lifecycle.resume()
            val home = container.home()
            home.onOpenImport(TEST_LINK)

            home.onOpenSession()
            container.onBack()

            assertSame(home, container.home())
            assertEquals(importSeed, home.state.value.importSheet)
        }
    }

    @Test
    fun `toggling while connected asks the container to stop the tunnel`() = runTest {
        val graph = TestGraph()
        graph.client.connectionState.value = testConnected()
        var starts = 0
        var stops = 0
        withComponentContext { lifecycle, context ->
            val container = graph.container(
                context,
                onStartVpn = OnStartVpn { starts++ },
                onStopVpn = OnStopVpn { stops++ },
            )
            lifecycle.resume()

            container.home().onToggleConnection()

            assertEquals(1, stops)
            assertEquals(0, starts)
        }
    }
}
