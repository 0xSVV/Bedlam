package ru.shapovalov.bedlam.core.geoip.data

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode

/** No-op [GeoIpDatabase] for testing or when no database is bound. */
@Inject
class NoopGeoIpDatabase : GeoIpDatabase {
    override suspend fun availableCountries(): List<CountryCode> = emptyList()
    override suspend fun cidrs(country: CountryCode): List<Cidr> = emptyList()
    override suspend fun isLoaded(): Boolean = false
}
