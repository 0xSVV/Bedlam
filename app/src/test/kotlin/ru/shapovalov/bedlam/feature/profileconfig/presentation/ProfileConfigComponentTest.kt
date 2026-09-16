package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.instancekeeper.InstanceKeeperDispatcher
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardContainerComponent
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.TestGraph
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.bedlam.testing.withComponentContext

@ExtendWith(MainDispatcherExtension::class)
class ProfileConfigComponentTest {

    private val profile = testProfile("p1", name = "Home")

    private class Closes : ProfileConfigComponent.OnBack {
        var count = 0
        override fun invoke() {
            count++
        }
    }

    private fun graph(): TestGraph = TestGraph().apply { profiles.profiles.value = listOf(profile) }

    private fun TestGraph.editor(
        context: ComponentContext,
        closes: Closes = Closes(),
    ): ProfileConfigComponent = profileConfigFactory.create(context, profile.id, closes)

    private fun TestGraph.container(context: ComponentContext): DashboardContainerComponent =
        dashboardContainerFactory.create(
            context,
            DashboardContainerComponent.OnStartVpn {},
            DashboardContainerComponent.OnStopVpn {},
        )

    private fun DashboardContainerComponent.editor(): ProfileConfigComponent =
        assertInstanceOf(
            DashboardContainerComponent.Child.ProfileConfig::class.java,
            childStack.value.active.instance,
        ).component

    @Test
    fun `back in view mode closes the screen`() = runTest {
        val graph = graph()
        val closes = Closes()
        withComponentContext { _, context ->
            val editor = graph.editor(context, closes)

            editor.onBackPressed()

            assertEquals(1, closes.count)
        }
    }

    @Test
    fun `back in edit mode with changes asks instead of closing`() = runTest {
        val graph = graph()
        val closes = Closes()
        withComponentContext { _, context ->
            val editor = graph.editor(context, closes)
            editor.onEnterEditMode()
            editor.onDraftNameChanged("Work")

            editor.onBackPressed()

            assertEquals(0, closes.count)
            val state = editor.state.value
            assertTrue(state.pendingDiscardConfirmation)
            assertTrue(state.editMode)
            assertEquals("Work", state.draftName)
        }
    }

    @Test
    fun `back in edit mode without changes leaves edit mode and stays open`() = runTest {
        val graph = graph()
        val closes = Closes()
        withComponentContext { _, context ->
            val editor = graph.editor(context, closes)
            editor.onEnterEditMode()

            editor.onBackPressed()

            assertEquals(0, closes.count)
            assertFalse(editor.state.value.editMode)
            assertFalse(editor.state.value.pendingDiscardConfirmation)
        }
    }

    @Test
    fun `discarding after back restores the profile and a second back closes`() = runTest {
        val graph = graph()
        val closes = Closes()
        withComponentContext { _, context ->
            val editor = graph.editor(context, closes)
            editor.onEnterEditMode()
            editor.onDraftNameChanged("Work")
            editor.onBackPressed()

            editor.onDiscardChanges()

            assertEquals(0, closes.count)
            assertFalse(editor.state.value.editMode)
            assertEquals("Home", editor.state.value.draftName)

            editor.onBackPressed()

            assertEquals(1, closes.count)
        }
    }

    @Test
    fun `closing skips the discard prompt`() = runTest {
        val graph = graph()
        val closes = Closes()
        withComponentContext { _, context ->
            val editor = graph.editor(context, closes)
            editor.onEnterEditMode()
            editor.onDraftNameChanged("Work")

            editor.onClose()

            assertEquals(1, closes.count)
            assertFalse(editor.state.value.pendingDiscardConfirmation)
        }
    }

    @Test
    fun `a pending discard survives a configuration change`() = runTest {
        val graph = graph()
        val stateKeeper = StateKeeperDispatcher()
        val instanceKeeper = InstanceKeeperDispatcher()
        try {
            val savedState = withComponentContext(stateKeeper, instanceKeeper) { lifecycle, context ->
                val container = graph.container(context)
                lifecycle.resume()
                assertInstanceOf(
                    DashboardContainerComponent.Child.Root::class.java,
                    container.childStack.value.active.instance,
                ).component.onOpenProfileConfig(profile.id)
                val editor = container.editor()
                editor.onEnterEditMode()
                editor.onDraftNameChanged("Work")
                editor.onBackPressed()
                stateKeeper.save()
            }

            withComponentContext(StateKeeperDispatcher(savedState), instanceKeeper) { lifecycle, context ->
                val container = graph.container(context)
                lifecycle.resume()

                val state = container.editor().state.value
                assertTrue(state.pendingDiscardConfirmation)
                assertTrue(state.editMode)
                assertEquals("Work", state.draftName)
            }
        } finally {
            instanceKeeper.destroy()
        }
    }

    @Test
    fun `cancel with changes asks before discarding`() = runTest {
        val graph = graph()
        val closes = Closes()
        withComponentContext { _, context ->
            val editor = graph.editor(context, closes)
            editor.onEnterEditMode()
            editor.onDraftNameChanged("Work")

            editor.onCancelEdit()

            assertEquals(0, closes.count)
            assertTrue(editor.state.value.pendingDiscardConfirmation)
            assertTrue(editor.state.value.editMode)

            editor.onKeepEditing()

            assertFalse(editor.state.value.pendingDiscardConfirmation)
            assertEquals("Work", editor.state.value.draftName)
        }
    }
}
