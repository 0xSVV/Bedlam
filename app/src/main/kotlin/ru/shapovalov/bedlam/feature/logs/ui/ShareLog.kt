package ru.shapovalov.bedlam.feature.logs.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.logs.data.LogExportDevice
import ru.shapovalov.hysteria.api.HysteriaCore
import java.io.File
import java.time.ZonedDateTime

private val exportLock = Mutex()

internal suspend fun Context.shareLog(
    export: suspend (LogExportDevice, ZonedDateTime) -> String,
) {
    if (!exportLock.tryLock()) return
    val uri = try {
        withContext(Dispatchers.IO) {
            val text = export(exportDevice(), ZonedDateTime.now())
            val directory = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(directory, "bedlam-log.txt")
            file.writeText(text)
            FileProvider.getUriForFile(this@shareLog, "$packageName.fileprovider", file)
        }
    } finally {
        exportLock.unlock()
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(
        Intent.createChooser(send, getString(R.string.logs_action_share_cd))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.exportDevice(): LogExportDevice {
    val packageInfo = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
    return LogExportDevice(
        appVersionName = packageInfo?.versionName.orEmpty(),
        appVersionCode = packageInfo?.longVersionCode ?: 0L,
        coreVersion = HysteriaCore.VERSION,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
    )
}
