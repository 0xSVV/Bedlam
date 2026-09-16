package ru.shapovalov.bedlam.feature.profileconfig.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.reduceAll
import ru.shapovalov.bedlam.testing.testConfig
import ru.shapovalov.bedlam.testing.testProfile

class ProfileConfigReducerTest {

    private val profile = testProfile("p1", name = "Home")

    private val loaded = ProfileConfigStore.State(
        profileId = "p1",
        original = profile,
        draft = profile.config,
        draftName = profile.name,
        isLoading = false,
    )

    private val edited = loaded.copy(
        editMode = true,
        draft = testConfig("other.example:443"),
        draftName = "Work",
    )

    @Test
    fun `the first load seeds the draft and stops loading`() {
        val state = ProfileConfigReducer.reduceAll(
            ProfileConfigStore.State(profileId = "p1"),
            Msg.ProfileLoaded(profile),
        )

        assertEquals(loaded, state)
        assertFalse(state.isDirty)
    }

    @Test
    fun `a missing profile is marked not found`() {
        val state = ProfileConfigReducer.reduceAll(
            ProfileConfigStore.State(profileId = "p1"),
            Msg.ProfileMissing,
        )

        assertEquals(
            ProfileConfigStore.State(profileId = "p1", isLoading = false, notFound = true),
            state,
        )
    }

    @Test
    fun `entering edit mode clears a previous error`() {
        val state = ProfileConfigReducer.reduceAll(
            loaded.copy(saveError = "boom"),
            Msg.EditModeEntered,
        )

        assertEquals(loaded.copy(editMode = true), state)
    }

    @Test
    fun `draft messages change only the draft`() {
        val config = testConfig("other.example:443")

        val state = ProfileConfigReducer.reduceAll(
            loaded.copy(editMode = true),
            Msg.DraftUpdated(config),
            Msg.DraftNameUpdated("Work"),
        )

        assertEquals(edited, state)
        assertTrue(state.isDirty)
        assertTrue(state.canSave)
    }

    @Test
    fun `discarding restores the saved profile and leaves edit mode`() {
        val state = ProfileConfigReducer.reduceAll(
            edited.copy(saveError = "boom"),
            Msg.ChangesDiscarded,
        )

        assertEquals(loaded, state)
        assertFalse(state.isDirty)
    }

    @Test
    fun `save messages track saving and its error`() {
        val saving = ProfileConfigReducer.reduceAll(edited.copy(saveError = "old"), Msg.SaveStarted)
        assertEquals(edited.copy(isSaving = true), saving)

        val failed = ProfileConfigReducer.reduceAll(saving, Msg.SaveFailed("disk full"))
        assertEquals(edited.copy(saveError = "disk full"), failed)

        val dismissed = ProfileConfigReducer.reduceAll(failed, Msg.ErrorDismissed)
        assertEquals(edited, dismissed)
    }

    @Test
    fun `delete messages track the confirmation and deleting`() {
        val requested = ProfileConfigReducer.reduceAll(loaded, Msg.DeleteRequested)
        assertTrue(requested.pendingDeleteConfirmation)

        val cancelled = ProfileConfigReducer.reduceAll(requested, Msg.DeleteCancelled)
        assertEquals(loaded, cancelled)

        val deleting = ProfileConfigReducer.reduceAll(requested, Msg.DeleteStarted)
        assertEquals(loaded.copy(isDeleting = true), deleting)
    }

    @Test
    fun `a failed delete re-enables delete and reports the error`() {
        val state = ProfileConfigReducer.reduceAll(
            loaded.copy(isDeleting = true),
            Msg.DeleteFailed("locked"),
        )

        assertEquals(loaded.copy(saveError = "locked"), state)
    }
}
