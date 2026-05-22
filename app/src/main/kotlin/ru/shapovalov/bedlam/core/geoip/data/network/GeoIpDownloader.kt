package ru.shapovalov.bedlam.core.geoip.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadResult(val bytes: ByteArray, val sha256: String, val sizeBytes: Long) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = sha256.hashCode()
}

/** Streams a remote file into memory while computing SHA-256, reporting progress. */
@Inject
class GeoIpDownloader {

    suspend fun download(
        url: String,
        progress: MutableStateFlow<GeoIpUpdateState>,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Bedlam/1.0")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} downloading $url")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8 * 1024)
            val out = java.io.ByteArrayOutputStream(totalBytes?.toInt()?.coerceAtMost(MAX_SIZE_BYTES) ?: 8 * 1024)
            conn.inputStream.use { input ->
                var received = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    received += n
                    if (received > MAX_SIZE_BYTES) {
                        throw IOException("GeoIP file too large (>${MAX_SIZE_BYTES / 1024 / 1024} MB)")
                    }
                    progress.value = GeoIpUpdateState.Downloading(received, totalBytes)
                }
                val bytes = out.toByteArray()
                DownloadResult(
                    bytes = bytes,
                    sha256 = digest.digest().toHex(),
                    sizeBytes = bytes.size.toLong(),
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_SIZE_BYTES = 64 * 1024 * 1024 // 64 MB hard cap
    }
}
