package ru.shapovalov.bedlam.core.appfilter.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository

@Inject
class ToggleAppFilterPackageUseCase(private val repository: AppFilterRepository) {
    suspend operator fun invoke(pkg: String) = repository.togglePackage(pkg)
}
