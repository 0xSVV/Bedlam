package ru.shapovalov.bedlam.core.geoip.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoIpDao {

    @Query("SELECT * FROM geoip_db_info WHERE id = 1")
    fun observeInfo(): Flow<GeoIpDbInfoEntity?>

    @Query("SELECT * FROM geoip_db_info WHERE id = 1")
    suspend fun getInfo(): GeoIpDbInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInfo(info: GeoIpDbInfoEntity)

    @Query("DELETE FROM geoip_db_info")
    suspend fun clearInfo()
}
