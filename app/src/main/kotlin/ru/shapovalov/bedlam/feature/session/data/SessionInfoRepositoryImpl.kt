package ru.shapovalov.bedlam.feature.session.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository
import java.net.HttpURLConnection
import java.net.URL

@Inject
class SessionInfoRepositoryImpl(
    private val json: Json,
) : SessionInfoRepository {

    override suspend fun fetch(): Result<SessionInfo> = runCatching {
        coroutineScope {
            val primary = async(Dispatchers.IO) { fetchIpApi() }
            val v6 = async(Dispatchers.IO) { runCatching { fetchIpv6() }.getOrNull() }

            val info = primary.await()
            val primaryIsV6 = info.ip?.contains(':') == true
            val ipv4 = info.ip?.takeIf { !primaryIsV6 }
            val ipv6 = v6.await() ?: info.ip?.takeIf { primaryIsV6 }

            SessionInfo(
                ipv4 = ipv4,
                ipv6 = ipv6,
                asn = info.asn?.removePrefix("AS"),
                asOrganization = info.org,
                country = info.country,
                city = info.city,
                region = info.region,
                latitude = info.latitude,
                longitude = info.longitude,
            )
        }
    }

    private suspend fun fetchIpApi(): IpApiDto = withContext(Dispatchers.IO) {
        val body = httpGet(URL("https://ipapi.co/json/"))
        json.decodeFromString(IpApiDto.serializer(), body)
    }

    private suspend fun fetchIpv6(): String? = withContext(Dispatchers.IO) {
        val body = httpGet(URL("https://api64.ipify.org?format=json"))
        json.decodeFromString(IpifyDto.serializer(), body).ip?.takeIf { it.contains(':') }
    }

    private fun httpGet(url: URL): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Bedlam/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
