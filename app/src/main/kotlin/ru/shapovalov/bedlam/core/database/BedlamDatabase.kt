package ru.shapovalov.bedlam.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsDao
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsEntity
import ru.shapovalov.bedlam.core.profile.data.local.HysteriaConfigConverter
import ru.shapovalov.bedlam.core.profile.data.local.ProfileDao
import ru.shapovalov.bedlam.core.profile.data.local.ProfileEntity
import ru.shapovalov.bedlam.core.routing.data.local.ResolvedCidrEntity
import ru.shapovalov.bedlam.core.routing.data.local.RouteSourceEntity
import ru.shapovalov.bedlam.core.routing.data.local.RoutingConfigEntity
import ru.shapovalov.bedlam.core.routing.data.local.RoutingDao

@Database(
    entities = [
        ProfileEntity::class,
        AppSettingsEntity::class,
        RoutingConfigEntity::class,
        RouteSourceEntity::class,
        ResolvedCidrEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(HysteriaConfigConverter::class)
abstract class BedlamDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun routingDao(): RoutingDao
}
