package ru.shapovalov.bedlam.feature.logs.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.logs.data.LogExportHeader
import ru.shapovalov.bedlam.feature.logs.data.formatLogExport
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.io.File

internal fun Context.shareLog(entries: List<LogEntry>, minLevel: LogLevel, droppedCount: Long) {
    val text = formatLogExport(
        entries = entries,
        header = LogExportHeader(
            appVersion = appVersionName(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            minLevel = minLevel,
            droppedCount = droppedCount,
        ),
    )
    val directory = File(cacheDir, "logs").apply { mkdirs() }
    val file = File(directory, "bedlam-log.txt")
    file.writeText(text)

    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
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

private fun Context.appVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
}.getOrDefault("")
