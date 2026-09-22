package ru.shapovalov.bedlam.core.crash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import ru.shapovalov.bedlam.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashRecorder(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, DIRECTORY)

    private val reportFile: File
        get() = File(directory, REPORT_FILE)

    private val seenMarker: File
        get() = File(directory, SEEN_MARKER)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun unreportedCrash(): String? {
        if (!reportFile.isFile || seenMarker.isFile) return null
        val report = runCatching { reportFile.readText() }.getOrNull() ?: return null
        runCatching { seenMarker.createNewFile() }
        return report.lineSequence().take(MAX_LOG_LINES).joinToString("\n").trimEnd()
    }

    private fun record(thread: Thread, throwable: Throwable) {
        directory.mkdirs()
        seenMarker.delete()
        val report = formatCrashReport(
            header = "Bedlam ${appVersionName()}\n${Build.MANUFACTURER} ${Build.MODEL} · Android SDK ${Build.VERSION.SDK_INT}",
            thread = thread,
            throwable = throwable,
            timestampMillis = System.currentTimeMillis(),
        )
        reportFile.writeText(report)
        postNotification(report)
    }

    private fun postNotification(report: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.crash_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", reportFile)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.crash_notification_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = PendingIntent.getActivity(
            context,
            0,
            chooser,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bedlam)
            .setContentTitle(context.getString(R.string.crash_notification_title))
            .setContentText(context.getString(R.string.crash_notification_text))
            .setStyle(Notification.BigTextStyle().bigText(report.lineSequence().take(NOTIFICATION_LINES).joinToString("\n")))
            .setContentIntent(share)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        private const val DIRECTORY = "crash"
        private const val REPORT_FILE = "last-crash.txt"
        private const val SEEN_MARKER = "last-crash.seen"
        private const val CHANNEL_ID = "bedlam_crash"
        private const val NOTIFICATION_ID = 3
        private const val MAX_LOG_LINES = 40
        private const val NOTIFICATION_LINES = 6
    }
}

internal fun formatCrashReport(
    header: String,
    thread: Thread,
    throwable: Throwable,
    timestampMillis: Long,
): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
    val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
    return buildString {
        appendLine(header)
        appendLine("Crashed at $stamp on thread ${thread.name}")
        appendLine()
        append(trace.replace("\r\n", "\n"))
    }
}
