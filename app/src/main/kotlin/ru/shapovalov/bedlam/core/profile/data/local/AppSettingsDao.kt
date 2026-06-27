package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.Dao
import androidx.room.Query
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

    @Query(
        """
        INSERT INTO app_settings (id, activeProfileId, appFilterMode, appFilterPackages)
        VALUES (0, :id, 'ALL', '')
        ON CONFLICT(id) DO UPDATE SET activeProfileId = :id
        """,
    )
    suspend fun setActiveProfileId(id: String?)

    @Query(
        """
        INSERT INTO app_settings (id, activeProfileId, appFilterMode, appFilterPackages)
        VALUES (0, NULL, :mode, '')
        ON CONFLICT(id) DO UPDATE SET appFilterMode = :mode
        """,
    )
    suspend fun setAppFilterMode(mode: String)

    @Query(
        """
        INSERT INTO app_settings (id, activeProfileId, appFilterMode, appFilterPackages)
        VALUES (0, NULL, 'ALL', :packages)
        ON CONFLICT(id) DO UPDATE SET appFilterPackages = :packages
        """,
    )
    suspend fun setAppFilterPackages(packages: String)
}
