package ru.shapovalov.bedlam.core.appfilter.domain.usecase

import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository

@Inject
class ObserveAppFilterUseCase(private val repository: AppFilterRepository) {
    operator fun invoke(): Flow<AppFilter> = repository.observe()
}
