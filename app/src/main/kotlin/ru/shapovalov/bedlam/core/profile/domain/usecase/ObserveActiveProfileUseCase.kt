package ru.shapovalov.bedlam.core.profile.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

@Inject
class ObserveActiveProfileUseCase(private val repository: ProfileRepository) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<Profile?> =
        repository.observeActiveId().flatMapLatest { id ->
            if (id == null) flowOf(null) else repository.observe(id)
        }
}
