package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingsDao {

    @Query("SELECT activeProfileId FROM app_settings WHERE id = 0")
    fun observeActiveProfileId(): Flow<String?>

    @Query("SELECT activeProfileId FROM app_settings WHERE id = 0")
    suspend fun getActiveProfileId(): String?

    @Query("SELECT * FROM app_settings WHERE id = 0")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 0")
    suspend fun get(): AppSettingsEntity?

    @Upsert
    suspend fun upsert(entity: AppSettingsEntity)
}
