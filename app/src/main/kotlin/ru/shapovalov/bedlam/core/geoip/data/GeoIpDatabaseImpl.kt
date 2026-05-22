package ru.shapovalov.bedlam.core.geoip.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.geoip.data.parser.V2FlyGeoIpParser
import ru.shapovalov.bedlam.core.geoip.data.storage.GeoIpStorage
import ru.shapovalov.bedlam.core.geoip.domain.repository.GeoIpDatabase
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode

/** File-backed [GeoIpDatabase] with a parsed in-memory cache. */
@Inject
class GeoIpDatabaseImpl(
    private val storage: GeoIpStorage,
    private val parser: V2FlyGeoIpParser,
) : GeoIpDatabase {

    private val mutex = Mutex()
    @Volatile private var cache: Map<CountryCode, List<Cidr>>? = null
    @Volatile private var loadedSize: Long = -1L

    suspend fun reload() = mutex.withLock {
        cache = null
        loadedSize = -1L
    }

    override suspend fun availableCountries(): List<CountryCode> =
        ensureLoaded()?.keys?.sortedBy { it.raw } ?: emptyList()

    override suspend fun cidrs(country: CountryCode): List<Cidr> =
        ensureLoaded()?.get(country) ?: emptyList()

    override suspend fun isLoaded(): Boolean = ensureLoaded() != null

    private suspend fun ensureLoaded(): Map<CountryCode, List<Cidr>>? {
        cache?.let { return it }
        return mutex.withLock {
            cache?.let { return it }
            if (!storage.exists()) return null
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = storage.readBytes()
                    loadedSize = bytes.size.toLong()
                    parser.parse(bytes).also { cache = it }
                }.getOrElse { e ->
                    android.util.Log.w(TAG, "Failed to parse geoip.dat", e)
                    null
                }
            }
        }
    }

    companion object { private const val TAG = "GeoIpDb" }
}
