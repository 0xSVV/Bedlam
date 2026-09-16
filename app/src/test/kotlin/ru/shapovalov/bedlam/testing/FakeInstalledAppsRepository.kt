package ru.shapovalov.bedlam.testing

import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp
import ru.shapovalov.bedlam.core.appfilter.domain.repository.InstalledAppsRepository

class FakeInstalledAppsRepository(
    var apps: List<InstalledApp> = emptyList(),
) : InstalledAppsRepository {

    override suspend fun list(): List<InstalledApp> = apps
}
