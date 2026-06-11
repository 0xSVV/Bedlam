package ru.shapovalov.bedlam.feature.update.domain.repository

import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.feature.update.domain.model.InstallStatus
import java.io.File

interface UpdateInstaller {
    val status: StateFlow<InstallStatus>
    suspend fun install(apk: File)
    fun reset()
}
