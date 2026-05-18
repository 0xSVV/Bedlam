package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val activeProfileId: String?,
    @ColumnInfo(defaultValue = "ALL")
    val appFilterMode: String = "ALL",
    @ColumnInfo(defaultValue = "")
    val appFilterPackages: String = "",
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
