package ru.shapovalov.bedlam.feature.session.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.network.AppHttpClient
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository
import java.io.IOException

@Inject
class SessionInfoRepositoryImpl(
    private val httpClient: AppHttpClient,
    private val json: Json,
) : SessionInfoRepository {

    override suspend fun fetch(): Result<SessionInfo> = try {
        Result.success(
            withTimeout(LOOKUP_TIMEOUT_MS) {
                coroutineScope {
                    val geo = async(Dispatchers.IO) { runCatching { fetchIpApi() } }
                    val v4 = async(Dispatchers.IO) { runCatching { fetchIp(IPV4_URL) }.getOrNull() }
                    val v6 = async(Dispatchers.IO) { runCatching { fetchIp(IPV6_URL) }.getOrNull() }

                    val info = geo.await().getOrNull()
                    val primaryIp = info?.ip
                    val primaryIsV6 = primaryIp?.contains(':') == true
                    val ipv4 = v4.await() ?: primaryIp?.takeIf { !primaryIsV6 }
                    val ipv6 = v6.await() ?: primaryIp?.takeIf { primaryIsV6 }

                    if (ipv4 == null && ipv6 == null) {
                        throw IOException("Public IP lookup returned no address")
                    }

                    SessionInfo(
                        ipv4 = ipv4,
                        ipv6 = ipv6,
                        asn = info?.asn?.removePrefix("AS"),
                        asOrganization = info?.org,
                        country = info?.country,
                        city = info?.city,
                        region = info?.region,
                        latitude = info?.latitude,
                        longitude = info?.longitude,
                    )
                }
            }
        )
    } catch (e: TimeoutCancellationException) {
        Result.failure(IOException("Session lookup timed out after ${LOOKUP_TIMEOUT_MS / 1_000}s", e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun fetchIpApi(): IpApiDto =
        json.decodeFromString(
            IpApiDto.serializer(),
            httpClient.get(IP_API_URL, timeoutMs = HTTP_TIMEOUT_MS),
        )

    private fun fetchIp(url: String): String? =
        json.decodeFromString(
            IpifyDto.serializer(),
            httpClient.get(url, timeoutMs = HTTP_TIMEOUT_MS),
        )
            .ip
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 12_000L
        const val HTTP_TIMEOUT_MS = 5_000
        const val IP_API_URL = "https://ipapi.co/json/"
        const val IPV4_URL = "https://api4.ipify.org?format=json"
        const val IPV6_URL = "https://api6.ipify.org?format=json"
    }
}
