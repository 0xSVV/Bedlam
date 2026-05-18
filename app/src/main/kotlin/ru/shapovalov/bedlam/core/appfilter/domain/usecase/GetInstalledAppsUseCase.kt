package ru.shapovalov.bedlam.core.appfilter.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.core.appfilter.domain.repository.InstalledAppsRepository

@Inject
class GetInstalledAppsUseCase(private val repository: InstalledAppsRepository) {
    suspend operator fun invoke(): List<InstalledApp> = repository.list()
}
