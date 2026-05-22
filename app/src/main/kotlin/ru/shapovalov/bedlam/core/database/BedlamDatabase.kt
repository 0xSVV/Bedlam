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
    version = 4,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
    exportSchema = true,
)
@TypeConverters(HysteriaConfigConverter::class)
abstract class BedlamDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun routingDao(): RoutingDao
}

/**
 * v2 → v3 introduced direct_route + geoip_db_info; v3 → v4 drops them in
 * favor of the unified route_source / resolved_cidr scheme and removes the
 * geoDirectCountriesCsv column from routing_config (SQLite rebuild because
 * minSdk < 31 can't ALTER TABLE DROP COLUMN safely).
 */
val MIGRATION_2_4: Migration = object : Migration(2, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildRoutingConfig(db, fromVersion = 2)
        createRouteSourceTables(db)
    }
}

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rebuildRoutingConfig(db, fromVersion = 3)
        createRouteSourceTables(db)
        // Carry existing direct_route rows over as CIDR sources.
        db.execSQL(
            """
            INSERT INTO route_source (id, kind, rawValue, comment, enabled, orderIndex, lastResolvedMillis, lastError)
            SELECT id, 'CIDR', cidr, comment, enabled, orderIndex, NULL, NULL FROM direct_route
            """.trimIndent()
        )
        db.execSQL("DROP TABLE IF EXISTS direct_route")
        db.execSQL("DROP TABLE IF EXISTS geoip_db_info")
    }
}

private fun rebuildRoutingConfig(db: SupportSQLiteDatabase, fromVersion: Int) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS routing_config_new (
            id INTEGER PRIMARY KEY NOT NULL,
            bypassLan INTEGER NOT NULL DEFAULT 1,
            ipv6Mode TEXT NOT NULL DEFAULT 'Enabled',
            dnsMode TEXT NOT NULL DEFAULT 'Cloudflare',
            customDnsCsv TEXT NOT NULL DEFAULT ''
        )
        """.trimIndent()
    )
    if (fromVersion >= 3) {
        // v3 had geoDirectCountriesCsv; drop it via copy.
        db.execSQL(
            "INSERT INTO routing_config_new (id, bypassLan, ipv6Mode, dnsMode, customDnsCsv) " +
                "SELECT id, bypassLan, ipv6Mode, dnsMode, customDnsCsv FROM routing_config"
        )
    } else {
        // v2 didn't have routing_config at all (or it had no geo column); copy what's there.
        db.execSQL(
            "INSERT INTO routing_config_new (id, bypassLan, ipv6Mode, dnsMode, customDnsCsv) " +
                "SELECT id, bypassLan, ipv6Mode, dnsMode, customDnsCsv FROM routing_config"
        )
    }
    db.execSQL("DROP TABLE routing_config")
    db.execSQL("ALTER TABLE routing_config_new RENAME TO routing_config")
}

private fun createRouteSourceTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS route_source (
            id TEXT PRIMARY KEY NOT NULL,
            kind TEXT NOT NULL,
            rawValue TEXT NOT NULL,
            comment TEXT NOT NULL,
            enabled INTEGER NOT NULL,
            orderIndex INTEGER NOT NULL,
            lastResolvedMillis INTEGER,
            lastError TEXT
        )
        """.trimIndent()
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS resolved_cidr (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            sourceId TEXT NOT NULL,
            cidr TEXT NOT NULL,
            FOREIGN KEY(sourceId) REFERENCES route_source(id) ON DELETE CASCADE
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_resolved_cidr_sourceId ON resolved_cidr(sourceId)")
}
