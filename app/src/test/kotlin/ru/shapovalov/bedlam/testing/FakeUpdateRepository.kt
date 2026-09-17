package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.model.DownloadEvent
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository

class FakeUpdateRepository(
    var available: AppUpdate? = null,
    var latest: AppUpdate? = null,
    var installed: String = "1.5.3",
    var download: Flow<DownloadEvent> = emptyFlow(),
) : UpdateRepository {

    val skipped = mutableListOf<String>()

    override val availableVersion = MutableStateFlow<String?>(null)

    override fun installedVersion(): String = installed

    override suspend fun checkForUpdate(): AppUpdate? = available

    override suspend fun fetchUpdate(): AppUpdate? = latest

    override fun downloadApk(update: AppUpdate): Flow<DownloadEvent> = download

    override suspend fun skipVersion(versionName: String) {
        skipped += versionName
    }
}
