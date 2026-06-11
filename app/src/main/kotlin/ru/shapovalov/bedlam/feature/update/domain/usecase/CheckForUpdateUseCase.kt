package ru.shapovalov.bedlam.feature.update.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository

@Inject
class CheckForUpdateUseCase(private val repository: UpdateRepository) {
    suspend operator fun invoke(): AppUpdate? = repository.checkForUpdate()
}
