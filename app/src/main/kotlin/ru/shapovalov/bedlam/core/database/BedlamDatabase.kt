package ru.shapovalov.bedlam.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsDao
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsEntity
import ru.shapovalov.bedlam.core.profile.data.local.HysteriaConfigConverter
import ru.shapovalov.bedlam.core.profile.data.local.ProfileDao
import ru.shapovalov.bedlam.core.profile.data.local.ProfileEntity

@Database(
    entities = [ProfileEntity::class, AppSettingsEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(HysteriaConfigConverter::class)
abstract class BedlamDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun appSettingsDao(): AppSettingsDao
}
