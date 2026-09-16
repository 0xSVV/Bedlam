package ru.shapovalov.bedlam.navigation

import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore
import ru.shapovalov.bedlam.navigation.RootComponent.Child
import ru.shapovalov.bedlam.navigation.RootComponent.Tab
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TEST_LINK
import ru.shapovalov.bedlam.testing.TestGraph
import ru.shapovalov.bedlam.testing.appUpdate
import ru.shapovalov.bedlam.testing.withComponentContext

@ExtendWith(MainDispatcherExtension::class)
class RootComponentTest {

    private val linkSeed = DashboardStore.ImportSheetSeed(TEST_LINK, ProfileImportFormat.Link)

    private fun RootComponent.tabs(): List<Tab> = childStack.value.items.map { it.instance.tab }

    private fun RootComponent.activeChild(): Child = childStack.value.active.instance

    private fun RootComponent.updateScreens(): Int =
        childStack.value.items.count { it.instance is Child.Update }

    private fun RootComponent.home(): DashboardComponent {
        val container = assertInstanceOf(Child.Dashboard::class.java, activeChild()).component
        return assertInstanceOf(
            DashboardContainerComponent.Child.Root::class.java,
            container.childStack.value.active.instance,
        ).component
    }

    @Test
    fun `starts on the dashboard`() = runTest {
        val graph = TestGraph()
        withComponentContext { _, context ->
            val root = graph.root(context)

            assertInstanceOf(Child.Dashboard::class.java, root.activeChild())
            assertEquals(1, root.childStack.value.items.size)
        }
    }

    @Test
    fun `selecting tabs brings each to the front without duplicates`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()

            root.onTabSelected(Tab.Settings)
            root.onTabSelected(Tab.Logs)
            root.onTabSelected(Tab.Dashboard)

            assertEquals(listOf(Tab.Settings, Tab.Logs, Tab.Dashboard), root.tabs())
            assertInstanceOf(Child.Dashboard::class.java, root.activeChild())
        }
    }

    @Test
    fun `returning to a visited tab reuses its component`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()

            root.onTabSelected(Tab.Settings)
            val settings = assertInstanceOf(Child.Settings::class.java, root.activeChild())
            root.onTabSelected(Tab.Dashboard)
            root.onTabSelected(Tab.Settings)

            val reopened = assertInstanceOf(Child.Settings::class.java, root.activeChild())
            assertSame(settings.component, reopened.component)
            assertEquals(listOf(Tab.Dashboard, Tab.Settings), root.tabs())
        }
    }

    @Test
    fun `an import link from another tab opens the import sheet on the dashboard`() = runTest {
        val graph = TestGraph()
        withComponentContext { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()
            root.onTabSelected(Tab.Logs)

            root.onImportLink(TEST_LINK)

            assertEquals(linkSeed, root.home().state.value.importSheet)
        }
    }

    @Test
    fun `an import link carrying json is seeded as json`() = runTest {
        val graph = TestGraph()
        val json = """{"server":{"server":"example.com:443","auth":"pw"}}"""
        withComponentContext { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()

            root.onImportLink("  $json\n")

            assertEquals(
                DashboardStore.ImportSheetSeed(json, ProfileImportFormat.Json),
                root.home().state.value.importSheet,
            )
        }
    }

    @Test
    fun `an update found at start opens the update screen`() = runTest {
        val graph = TestGraph()
        graph.updates.available = appUpdate()
        withComponentContext { _, context ->
            val root = graph.root(context)

            assertInstanceOf(Child.Update::class.java, root.activeChild())
            assertEquals(1, root.updateScreens())
        }
    }

    @Test
    fun `a restored update screen is not opened a second time`() = runTest {
        val graph = TestGraph()
        graph.updates.available = appUpdate()
        val stateKeeper = StateKeeperDispatcher()
        val savedState = withComponentContext(stateKeeper) { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()
            root.onTabSelected(Tab.Settings)
            stateKeeper.save()
        }

        withComponentContext(StateKeeperDispatcher(savedState)) { _, context ->
            val root = graph.root(context)

            assertInstanceOf(Child.Settings::class.java, root.activeChild())
            assertEquals(1, root.updateScreens())
            assertEquals(3, root.childStack.value.items.size)
        }
    }

    @Test
    fun `dismissing the update returns to the dashboard`() = runTest {
        val graph = TestGraph()
        graph.updates.available = appUpdate()
        withComponentContext { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()

            assertInstanceOf(Child.Update::class.java, root.activeChild()).component.onBack()

            assertInstanceOf(Child.Dashboard::class.java, root.activeChild())
            assertEquals(0, root.updateScreens())
        }
    }

    @Test
    fun `an import link while the update screen is open reaches the dashboard`() = runTest {
        val graph = TestGraph()
        graph.updates.available = appUpdate()
        withComponentContext { lifecycle, context ->
            val root = graph.root(context)
            lifecycle.resume()

            root.onImportLink(TEST_LINK)

            assertInstanceOf(Child.Dashboard::class.java, root.activeChild())
            assertEquals(linkSeed, root.home().state.value.importSheet)
        }
    }
}
