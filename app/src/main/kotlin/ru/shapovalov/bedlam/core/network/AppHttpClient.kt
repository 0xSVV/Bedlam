package ru.shapovalov.bedlam.core.network

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.di.AppScope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@AppScope
@Inject
class AppHttpClient {

    fun get(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000
        const val USER_AGENT = "Bedlam/1.0"
    }
}
