package ru.shapovalov.bedlam.core.profile.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

@Inject
class SetActiveProfileUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(id: String?) {
        repository.setActiveId(id)
    }
}
