package ru.shapovalov.bedlam.core.geoip.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpDatabaseInfo
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState

interface GeoIpUpdater {
    val state: StateFlow<GeoIpUpdateState>
    fun observeInfo(): Flow<GeoIpDatabaseInfo>

    /** Download from [sourceUrl] (or [DEFAULT_SOURCE_URL]) and install atomically. */
    suspend fun downloadLatest(sourceUrl: String? = null): Result<GeoIpDatabaseInfo>

    suspend fun remove()

    companion object {
        const val DEFAULT_SOURCE_URL: String =
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    }
}
