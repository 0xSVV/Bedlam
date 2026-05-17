package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val activeProfileId: String?,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
