package ru.shapovalov.bedlam.core.geoip.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geoip_db_info")
data class GeoIpDbInfoEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val version: String? = null,
    val sha256: String? = null,
    val lastUpdatedMillis: Long? = null,
    val sourceUrl: String? = null,
    val sizeBytes: Long? = null,
) {
    companion object { const val SINGLETON_ID = 1 }
}
