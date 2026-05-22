package ru.shapovalov.bedlam.core.geoip.domain.repository

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode

/** Read-side of the GeoIP database. */
interface GeoIpDatabase {

    /** Available country codes; empty if no database is loaded. */
    suspend fun availableCountries(): List<CountryCode>

    /** CIDRs for [country]; empty if absent or no database is loaded. */
    suspend fun cidrs(country: CountryCode): List<Cidr>

    suspend fun isLoaded(): Boolean
}
