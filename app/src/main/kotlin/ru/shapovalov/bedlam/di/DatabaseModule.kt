package ru.shapovalov.bedlam.di

import android.content.Context
import androidx.room.Room
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.database.BedlamDatabase
import ru.shapovalov.bedlam.core.geoip.data.local.GeoIpDao
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsDao
import ru.shapovalov.bedlam.core.profile.data.local.HysteriaConfigConverter
import ru.shapovalov.bedlam.core.profile.data.local.ProfileDao
import ru.shapovalov.bedlam.core.routing.data.local.RoutingDao

interface DatabaseModule {

    @AppScope
    @Provides
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @AppScope
    @Provides
    fun provideHysteriaConfigConverter(json: Json): HysteriaConfigConverter =
        HysteriaConfigConverter(json)

    @AppScope
    @Provides
    fun provideBedlamDatabase(
        context: Context,
        converter: HysteriaConfigConverter,
    ): BedlamDatabase = Room
        .databaseBuilder(context, BedlamDatabase::class.java, "bedlam.db")
        .addTypeConverter(converter)
        .build()

    @Provides
    fun provideProfileDao(database: BedlamDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideAppSettingsDao(database: BedlamDatabase): AppSettingsDao = database.appSettingsDao()

    @Provides
    fun provideRoutingDao(database: BedlamDatabase): RoutingDao = database.routingDao()

    @Provides
    fun provideGeoIpDao(database: BedlamDatabase): GeoIpDao = database.geoIpDao()
}
