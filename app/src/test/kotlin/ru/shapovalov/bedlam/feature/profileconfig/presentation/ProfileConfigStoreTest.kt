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
import ru.shapovalov.bedlam.testing.testProfile

@ExtendWith(MainDispatcherExtension::class)
class ProfileConfigStoreTest {

    private fun store(repository: FakeProfileRepository, profileId: String): ProfileConfigStore =
        ProfileConfigStoreFactory(
            DefaultStoreFactory(),
            ObserveProfileUseCase(repository),
            SaveProfileUseCase(repository),
            DeleteProfileUseCase(repository),
            FakeHysteriaClient(),
        ).create(profileId)

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
