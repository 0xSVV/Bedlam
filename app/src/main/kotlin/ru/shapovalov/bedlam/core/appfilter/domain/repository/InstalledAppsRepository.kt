package ru.shapovalov.bedlam.core.appfilter.domain.repository

import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp

interface InstalledAppsRepository {
    suspend fun list(): List<InstalledApp>
}
