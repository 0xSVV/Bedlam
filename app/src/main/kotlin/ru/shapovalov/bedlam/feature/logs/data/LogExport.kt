package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogExportHeader(
    val appVersion: String,
    val device: String,
    val sdkInt: Int,
    val minLevel: LogLevel,
    val droppedCount: Long,
)

fun formatLogExport(entries: List<LogEntry>, header: LogExportHeader): String {
    val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return buildString {
        appendLine("Bedlam ${header.appVersion}")
        appendLine("${header.device} · Android SDK ${header.sdkInt}")
        appendLine("Filter ${header.minLevel} · ${entries.size} lines")
        if (header.droppedCount > 0L) {
            appendLine("${header.droppedCount} earlier lines dropped")
        }
        appendLine()
        entries.forEach { entry ->
            append(stamp.format(Date(entry.timestampMillis)))
            append(' ')
            append(entry.level.name)
            append(' ')
            if (entry.source.isNotBlank()) {
                append(entry.source)
                append(' ')
            }
            appendLine(entry.message)
        }
    }
}
