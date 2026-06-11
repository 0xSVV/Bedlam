package ru.shapovalov.bedlam.feature.update.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.model.DownloadEvent

interface UpdateRepository {
    fun installedVersion(): String
    suspend fun checkForUpdate(): AppUpdate?
    fun downloadApk(update: AppUpdate): Flow<DownloadEvent>
    suspend fun skipVersion(versionName: String)
}
