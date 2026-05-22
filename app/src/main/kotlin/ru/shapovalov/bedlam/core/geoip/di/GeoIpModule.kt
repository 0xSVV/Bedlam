package ru.shapovalov.bedlam.core.geoip.di

import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.geoip.data.NoopGeoIpDatabase
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.di.AppScope

interface GeoIpModule {

    @AppScope
    @Provides
    fun bindGeoIpDatabase(impl: NoopGeoIpDatabase): GeoIpDatabase = impl
}
