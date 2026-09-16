package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
