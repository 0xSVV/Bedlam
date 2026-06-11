package ru.shapovalov.bedlam.feature.update.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.network.AppHttpClient
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate
import ru.shapovalov.bedlam.feature.update.domain.model.DownloadEvent
import ru.shapovalov.bedlam.feature.update.domain.repository.UpdateRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "update",
)

@Inject
class UpdateRepositoryImpl(
    private val context: Context,
    private val httpClient: AppHttpClient,
    private val json: Json,
) : UpdateRepository {

    private val dataStore = context.applicationContext.updateDataStore

    override fun installedVersion(): String {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        return info.versionName.orEmpty()
    }

    override suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val release = json.decodeFromString(
            GitHubReleaseDto.serializer(),
            httpClient.get(LATEST_RELEASE_URL),
        )
        val latestVersion = release.tagName.removePrefix("v")
        if (!isNewer(candidate = latestVersion, installed = installedVersion())) {
            return@withContext null
        }
        if (latestVersion == skippedVersion()) return@withContext null
        val asset = pickAsset(release.assets, latestVersion) ?: return@withContext null
        AppUpdate(
            versionName = latestVersion,
            releaseNotes = release.body.orEmpty().trim(),
            assetName = asset.name,
            downloadUrl = asset.downloadUrl,
            sizeBytes = asset.size,
        )
    }

    override fun downloadApk(update: AppUpdate): Flow<DownloadEvent> = flow {
        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        dir.deleteRecursively()
        dir.mkdirs()
        if (!dir.isDirectory) throw IOException("Cannot create $dir")
        val target = File(dir, update.assetName)

        val conn = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = DOWNLOAD_TIMEOUT_MS
            readTimeout = DOWNLOAD_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${update.downloadUrl}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
            var downloaded = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        emit(DownloadEvent.Progress(downloaded, total))
                    }
                }
            }
            emit(DownloadEvent.Completed(target))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun skipVersion(versionName: String) {
        dataStore.edit { prefs -> prefs[KEY_SKIPPED_VERSION] = versionName }
    }

    private suspend fun skippedVersion(): String? = dataStore.data.first()[KEY_SKIPPED_VERSION]

    private fun pickAsset(
        assets: List<GitHubReleaseDto.AssetDto>,
        version: String,
    ): GitHubReleaseDto.AssetDto? {
        Build.SUPPORTED_ABIS.forEach { abi ->
            assets.find { it.name == "bedlam-v$version-$abi.apk" }?.let { return it }
        }
        return assets.find { it.name == "bedlam-v$version-universal.apk" }
    }

    private fun isNewer(candidate: String, installed: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(installed) ?: return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseVersion(version: String): List<Int>? =
        NUMERIC_VERSION.find(version)?.value?.split('.')?.map { it.toInt() }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/0xSVV/Bedlam/releases/latest"
        const val DOWNLOAD_DIR = "updates"
        const val DOWNLOAD_TIMEOUT_MS = 30_000
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        val NUMERIC_VERSION = Regex("""\d+(?:\.\d+)*""")
        val KEY_SKIPPED_VERSION = stringPreferencesKey("skipped_version")
    }
}
