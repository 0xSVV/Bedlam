package ru.shapovalov.bedlam.feature.profileconfig.presentation

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
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.FakeProfileRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.testConfig
import ru.shapovalov.bedlam.testing.testProfile
import java.io.IOException

@ExtendWith(MainDispatcherExtension::class)
class ProfileConfigExecutorTest {

    private val profile = testProfile("p1", name = "Home")
    private val otherConfig = testConfig("other.example:443")

    private val loaded = ProfileConfigStore.State(
        profileId = "p1",
        original = profile,
        draft = profile.config,
        draftName = profile.name,
        isLoading = false,
    )

    private val edited = loaded.copy(editMode = true, draft = otherConfig, draftName = "Work")

    private fun store(
        state: ProfileConfigStore.State,
        repository: FakeProfileRepository,
        client: FakeHysteriaClient = FakeHysteriaClient(),
    ): Store<ProfileConfigStore.Intent, ProfileConfigStore.State, Nothing> =
        DefaultStoreFactory().create(
            initialState = state,
            executorFactory = {
                ProfileConfigExecutor(
                    SaveProfileUseCase(repository),
                    DeleteProfileUseCase(repository),
                    client,
                )
            },
            reducer = ProfileConfigReducer,
        )

    @Test
    fun `leaving edit mode with unsaved changes asks before discarding`() = runTest {
        store(edited, FakeProfileRepository(listOf(profile))).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.LeaveEditMode)

            assertEquals(edited.copy(pendingDiscardConfirmation = true), store.state)
        }
    }

    @Test
    fun `leaving edit mode without changes needs no confirmation`() = runTest {
        val repository = FakeProfileRepository(listOf(profile))
        store(loaded.copy(editMode = true), repository).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.LeaveEditMode)

            assertEquals(loaded, store.state)
        }
    }

    @Test
    fun `confirming the discard restores the saved values`() = runTest {
        store(edited, FakeProfileRepository(listOf(profile))).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.LeaveEditMode)
            store.accept(ProfileConfigStore.Intent.DiscardChanges)

            assertEquals(loaded, store.state)
        }
    }

    @Test
    fun `cancelling the discard keeps the edits`() = runTest {
        store(edited, FakeProfileRepository(listOf(profile))).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.LeaveEditMode)
            store.accept(ProfileConfigStore.Intent.CancelDiscard)

            assertEquals(edited, store.state)
        }
    }

    @Test
    fun `leaving edit mode while saving is ignored`() = runTest {
        val saving = edited.copy(isSaving = true)
        store(saving, FakeProfileRepository(listOf(profile))).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.LeaveEditMode)

            assertEquals(saving, store.state)
        }
    }

    @Test
    fun `leaving edit mode in view mode is ignored`() = runTest {
        val viewing = loaded.copy(draftName = "Work")
        store(viewing, FakeProfileRepository(listOf(profile))).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.LeaveEditMode)

            assertEquals(viewing, store.state)
        }
    }

    @Test
    fun `save persists the draft and leaves edit mode`() = runTest {
        val repository = FakeProfileRepository(listOf(profile))
        store(edited.copy(draftName = "  Work "), repository).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.Save)

            val saved = repository.profiles.value.single()
            assertEquals("p1", saved.id)
            assertEquals("Work", saved.name)
            assertEquals(otherConfig, saved.config)
            assertEquals(saved, store.state.original)
            assertFalse(store.state.editMode)
            assertFalse(store.state.isSaving)
            assertFalse(store.state.isDirty)
        }
    }

    @Test
    fun `save with a blank name fails without writing`() = runTest {
        val repository = FakeProfileRepository(listOf(profile))
        val client = FakeHysteriaClient()
        store(edited.copy(draftName = "   "), repository, client).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.Save)

            assertEquals("name must not be empty", store.state.saveError)
            assertEquals(0, repository.upsertCount)
            assertEquals(0, client.validateCalls)
            assertTrue(store.state.editMode)
        }
    }

    @Test
    fun `save with an invalid configuration reports the validation message`() = runTest {
        val repository = FakeProfileRepository(listOf(profile))
        val client = FakeHysteriaClient()
        client.validation = { Result.failure(IllegalArgumentException("bad sni")) }
        store(edited, repository, client).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.Save)

            assertEquals("bad sni", store.state.saveError)
            assertEquals(0, repository.upsertCount)
            assertFalse(store.state.isSaving)
            assertTrue(store.state.editMode)
        }
    }

    @Test
    fun `a second save while saving is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeProfileRepository(listOf(profile))
        repository.upsertGate = gate
        val client = FakeHysteriaClient()
        store(edited, repository, client).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.Save)
            assertTrue(store.state.isSaving)

            store.accept(ProfileConfigStore.Intent.Save)
            gate.complete(Unit)

            assertEquals(1, repository.upsertCount)
            assertEquals(1, client.validateCalls)
            assertFalse(store.state.isSaving)
            assertEquals("Work", repository.profiles.value.single().name)
        }
    }

    @Test
    fun `confirming delete removes the profile and clears it as active`() = runTest {
        val repository = FakeProfileRepository(listOf(profile), activeId = "p1")
        store(loaded.copy(pendingDeleteConfirmation = true), repository).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.ConfirmDelete)

            assertEquals(emptyList<Any>(), repository.profiles.value)
            assertNull(repository.active.value)
            assertTrue(store.state.isDeleting)
            assertFalse(store.state.pendingDeleteConfirmation)
        }
    }

    @Test
    fun `a failed delete reports the error and allows retry`() = runTest {
        val repository = FakeProfileRepository(listOf(profile))
        repository.deleteFailure = IOException("locked")
        store(loaded, repository).disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.ConfirmDelete)

            assertFalse(store.state.isDeleting)
            assertEquals("locked", store.state.saveError)
            assertEquals(listOf(profile), repository.profiles.value)

            repository.deleteFailure = null
            store.accept(ProfileConfigStore.Intent.ConfirmDelete)

            assertEquals(emptyList<Any>(), repository.profiles.value)
        }
    }
}
