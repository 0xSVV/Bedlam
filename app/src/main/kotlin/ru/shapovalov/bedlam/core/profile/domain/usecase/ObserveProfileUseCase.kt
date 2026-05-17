package ru.shapovalov.bedlam.core.profile.domain.usecase

import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

@Inject
class ObserveProfileUseCase(private val repository: ProfileRepository) {
    operator fun invoke(id: String): Flow<Profile?> = repository.observe(id)
}
