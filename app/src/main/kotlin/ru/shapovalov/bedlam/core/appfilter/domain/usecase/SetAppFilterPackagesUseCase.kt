package ru.shapovalov.bedlam.core.appfilter.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository

@Inject
class SetAppFilterPackagesUseCase(private val repository: AppFilterRepository) {
    suspend operator fun invoke(packages: Set<String>) = repository.setPackages(packages)
}
