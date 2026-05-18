package ru.shapovalov.bedlam.core.appfilter.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository

@Inject
class SetAppFilterModeUseCase(private val repository: AppFilterRepository) {
    suspend operator fun invoke(mode: AppFilterMode) = repository.setMode(mode)
}
