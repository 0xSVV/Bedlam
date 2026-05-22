package ru.shapovalov.bedlam.core.routing.data.resolver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Inject
class RipestatAsnResolver(private val json: Json) {

    suspend fun resolve(asn: Int): Result<List<Cidr>> = runCatching {
        withContext(Dispatchers.IO) {
            val url = URL("https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS$asn")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Bedlam/1.0")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    throw IOException("RIPEstat HTTP ${conn.responseCode} for AS$asn")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val response = json.decodeFromString(RipestatResponse.serializer(), body)
                response.data.prefixes.mapNotNull { Cidr.parseOrNull(it.prefix) }
            } finally {
                conn.disconnect()
            }
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
