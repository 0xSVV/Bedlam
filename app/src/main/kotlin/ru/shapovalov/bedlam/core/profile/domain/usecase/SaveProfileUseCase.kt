package ru.shapovalov.bedlam.core.profile.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

@Inject
class SaveProfileUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(profile: Profile): Profile {
        val touched = profile.copy(updatedAt = System.currentTimeMillis())
        repository.upsert(touched)
        return touched
    }
}
