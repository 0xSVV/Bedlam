package ru.shapovalov.bedlam.core.routing.data.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.network.AppHttpClient
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr

@Inject
class RipestatAsnResolver(
    private val httpClient: AppHttpClient,
    private val json: Json,
) {

    suspend fun resolve(asn: Int): Result<List<Cidr>> = runCatching {
        withContext(Dispatchers.IO) {
            val body = httpClient.get(
                url = "https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS$asn",
                timeoutMs = TIMEOUT_MS,
            )
            val response = json.decodeFromString(RipestatResponse.serializer(), body)
            response.data.prefixes.mapNotNull { Cidr.parseOrNull(it.prefix) }
        }
    }

    @Serializable
    private data class RipestatResponse(val data: Data)

    @Serializable
    private data class Data(val prefixes: List<Prefix> = emptyList())

    @Serializable
    private data class Prefix(val prefix: String)

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
