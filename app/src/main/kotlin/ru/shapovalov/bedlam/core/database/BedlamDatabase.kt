package ru.shapovalov.bedlam.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
@TypeConverters(HysteriaConfigConverter::class)
abstract class BedlamDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun routingDao(): RoutingDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routing_config ADD COLUMN directDnsMode TEXT NOT NULL DEFAULT 'System'")
                db.execSQL("ALTER TABLE routing_config ADD COLUMN directDnsTransport TEXT NOT NULL DEFAULT 'Udp'")
                db.execSQL("ALTER TABLE routing_config ADD COLUMN directCustomDnsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE routing_config ADD COLUMN dnsCacheEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}
