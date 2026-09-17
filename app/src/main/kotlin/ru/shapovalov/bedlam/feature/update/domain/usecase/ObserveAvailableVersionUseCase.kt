package ru.shapovalov.bedlam.feature.update.domain.usecase

import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository

@Inject
class ObserveAvailableVersionUseCase(private val repository: UpdateRepository) {
    operator fun invoke(): Flow<String?> = repository.availableVersion
}
