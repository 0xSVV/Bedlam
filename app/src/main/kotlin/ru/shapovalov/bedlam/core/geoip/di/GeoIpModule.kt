package ru.shapovalov.bedlam.core.geoip.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.geoip.data.GeoIpDatabaseImpl
import ru.shapovalov.bedlam.core.geoip.data.GeoIpUpdaterImpl
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpUpdater
import ru.shapovalov.bedlam.di.AppScope

interface GeoIpModule {

    @AppScope
    @Provides
    fun bindGeoIpDatabase(impl: GeoIpDatabaseImpl): GeoIpDatabase = impl

    @AppScope
    @Provides
    fun bindGeoIpUpdater(impl: GeoIpUpdaterImpl): GeoIpUpdater = impl
}
