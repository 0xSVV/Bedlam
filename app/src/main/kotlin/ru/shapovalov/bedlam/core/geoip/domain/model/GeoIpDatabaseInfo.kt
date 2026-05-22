package ru.shapovalov.bedlam.core.geoip.domain.model

data class GeoIpDatabaseInfo(
    val version: String?,
    val sha256: String?,
    val lastUpdatedMillis: Long?,
    val sourceUrl: String?,
    val sizeBytes: Long?,
) {
    val isInstalled: Boolean get() = sha256 != null

    companion object {
        val Empty: GeoIpDatabaseInfo = GeoIpDatabaseInfo(null, null, null, null, null)
    }
}
