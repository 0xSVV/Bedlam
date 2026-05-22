package ru.shapovalov.bedlam.core.geoip.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.geoip.data.local.GeoIpDao
import ru.shapovalov.bedlam.core.geoip.data.local.GeoIpDbInfoEntity
import ru.shapovalov.bedlam.core.geoip.data.network.GeoIpDownloader
import ru.shapovalov.bedlam.core.geoip.data.storage.GeoIpStorage
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpDatabaseInfo
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpUpdater

@Inject
class GeoIpUpdaterImpl(
    private val downloader: GeoIpDownloader,
    private val storage: GeoIpStorage,
    private val database: GeoIpDatabaseImpl,
    private val dao: GeoIpDao,
) : GeoIpUpdater {

    private val _state = MutableStateFlow<GeoIpUpdateState>(GeoIpUpdateState.Idle)
    override val state: StateFlow<GeoIpUpdateState> = _state.asStateFlow()

    private val mutex = Mutex()

    override fun observeInfo(): Flow<GeoIpDatabaseInfo> =
        dao.observeInfo().map { it?.toDomain() ?: GeoIpDatabaseInfo.Empty }

    override suspend fun downloadLatest(sourceUrl: String?): Result<GeoIpDatabaseInfo> = mutex.withLock {
        val url = sourceUrl ?: GeoIpUpdater.DEFAULT_SOURCE_URL
        _state.value = GeoIpUpdateState.Checking
        try {
            val result = downloader.download(url, _state)
            storage.replace(result.bytes)
            database.reload()
            val info = GeoIpDatabaseInfo(
                version = result.sha256.take(VERSION_PREFIX_LEN),
                sha256 = result.sha256,
                lastUpdatedMillis = System.currentTimeMillis(),
                sourceUrl = url,
                sizeBytes = result.sizeBytes,
            )
            dao.upsertInfo(info.toEntity())
            _state.value = GeoIpUpdateState.Idle
            Result.success(info)
        } catch (e: Exception) {
            _state.value = GeoIpUpdateState.Failed(e.message ?: "Download failed")
            Result.failure(e)
        }
    }

    override suspend fun remove() = mutex.withLock {
        storage.delete()
        database.reload()
        dao.clearInfo()
        _state.value = GeoIpUpdateState.Idle
    }

    private fun GeoIpDbInfoEntity.toDomain(): GeoIpDatabaseInfo = GeoIpDatabaseInfo(
        version = version,
        sha256 = sha256,
        lastUpdatedMillis = lastUpdatedMillis,
        sourceUrl = sourceUrl,
        sizeBytes = sizeBytes,
    )

    private fun GeoIpDatabaseInfo.toEntity(): GeoIpDbInfoEntity = GeoIpDbInfoEntity(
        version = version,
        sha256 = sha256,
        lastUpdatedMillis = lastUpdatedMillis,
        sourceUrl = sourceUrl,
        sizeBytes = sizeBytes,
    )

    private companion object {
        const val VERSION_PREFIX_LEN = 12
    }
}
