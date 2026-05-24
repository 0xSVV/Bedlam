package ru.shapovalov.bedlam.feature.session.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.network.AppHttpClient
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository

@Inject
class SessionInfoRepositoryImpl(
    private val httpClient: AppHttpClient,
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

    private fun fetchIpApi(): IpApiDto =
        json.decodeFromString(IpApiDto.serializer(), httpClient.get(IP_API_URL))

    private fun fetchIpv6(): String? =
        json.decodeFromString(IpifyDto.serializer(), httpClient.get(IPIFY_URL))
            .ip?.takeIf { it.contains(':') }

    private companion object {
        const val IP_API_URL = "https://ipapi.co/json/"
        const val IPIFY_URL = "https://api64.ipify.org?format=json"
    }
}
