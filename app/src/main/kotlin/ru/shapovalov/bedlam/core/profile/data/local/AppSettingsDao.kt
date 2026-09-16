package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
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
        INSERT INTO app_settings (id)
        SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM app_settings WHERE id = 0)
        """,
    )
    suspend fun insertDefaultsIfAbsent()

    @Query("UPDATE app_settings SET activeProfileId = :id WHERE id = 0")
    suspend fun updateActiveProfileId(id: String?)

    @Query("UPDATE app_settings SET appFilterMode = :mode WHERE id = 0")
    suspend fun updateAppFilterMode(mode: String)

    @Query("UPDATE app_settings SET appFilterPackages = :packages WHERE id = 0")
    suspend fun updateAppFilterPackages(packages: String)

    @Transaction
    suspend fun setActiveProfileId(id: String?) {
        insertDefaultsIfAbsent()
        updateActiveProfileId(id)
    }

    @Transaction
    suspend fun setAppFilterMode(mode: String) {
        insertDefaultsIfAbsent()
        updateAppFilterMode(mode)
    }

    @Transaction
    suspend fun setAppFilterPackages(packages: String) {
        insertDefaultsIfAbsent()
        updateAppFilterPackages(packages)
    }
}
