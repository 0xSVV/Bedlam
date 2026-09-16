package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.ObserveProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.FakeProfileRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.disposeAfter
import ru.shapovalov.bedlam.testing.idleReconnectProfile
import ru.shapovalov.bedlam.testing.testConfig
import ru.shapovalov.bedlam.testing.testProfile

@ExtendWith(MainDispatcherExtension::class)
class ProfileConfigStoreTest {

    private fun store(repository: FakeProfileRepository, profileId: String): ProfileConfigStore {
        val client = FakeHysteriaClient()
        return ProfileConfigStoreFactory(
            DefaultStoreFactory(),
            ObserveProfileUseCase(repository),
            SaveProfileUseCase(repository),
            DeleteProfileUseCase(repository),
            client,
            idleReconnectProfile(client, repository),
        ).create(profileId)
    }

    @Test
    fun `an external change reaches the draft in view mode`() = runTest {
        val profile = testProfile("p1", name = "Home")
        val repository = FakeProfileRepository(listOf(profile))
        store(repository, "p1").disposeAfter { store ->
            val changed = profile.copy(
                name = "Office",
                config = testConfig("office.example:443"),
                updatedAt = 1L,
            )

            repository.upsert(changed)

            assertEquals(changed, store.state.original)
            assertEquals(changed.config, store.state.draft)
            assertEquals("Office", store.state.draftName)
            assertFalse(store.state.isDirty)
        }
    }

    @Test
    fun `an external change keeps the edits in edit mode`() = runTest {
        val profile = testProfile("p1", name = "Home")
        val repository = FakeProfileRepository(listOf(profile))
        store(repository, "p1").disposeAfter { store ->
            store.accept(ProfileConfigStore.Intent.EnterEditMode)
            store.accept(ProfileConfigStore.Intent.UpdateDraftName("Mine"))
            val changed = profile.copy(
                name = "Office",
                config = testConfig("office.example:443"),
                updatedAt = 1L,
            )

            repository.upsert(changed)

            assertEquals(changed, store.state.original)
            assertEquals(profile.config, store.state.draft)
            assertEquals("Mine", store.state.draftName)
            assertTrue(store.state.isDirty)
        }
    }

    @Test
    fun `deleting the open profile marks it not found`() = runTest {
        val profile = testProfile("p1", name = "Home")
        val repository = FakeProfileRepository(listOf(profile, testProfile("p2")))
        store(repository, "p1").disposeAfter { store ->
            assertEquals(profile, store.state.original)
            assertFalse(store.state.notFound)

            store.accept(ProfileConfigStore.Intent.RequestDelete)
            store.accept(ProfileConfigStore.Intent.ConfirmDelete)

            assertTrue(store.state.notFound)
            assertEquals(listOf("p2"), repository.profiles.value.map { it.id })
        }
    }
}
