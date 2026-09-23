package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LogExportDevice(
    val appVersionName: String,
    val appVersionCode: Long,
    val coreVersion: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
)

data class LogExportHeader(
    val exportedAt: ZonedDateTime,
    val device: LogExportDevice,
    val connection: String,
    val routing: String,
    val dropped: DroppedLines,
    val addressesHidden: Boolean,
)

fun formatLogExport(entries: List<LogEntry>, header: LogExportHeader): String {
    val zone = header.exportedAt.zone
    val device = header.device
    val counts = LogLevel.entries.associateWith { level -> entries.count { it.level == level } }
    return buildString {
        appendLine(
            "Bedlam ${device.appVersionName} (${device.appVersionCode}) · Hysteria core ${device.coreVersion}"
        )
        appendLine("Exported ${EXPORTED_AT_FORMAT.format(header.exportedAt)}")
        appendLine(
            "${device.manufacturer} ${device.model} · Android ${device.androidRelease} " +
                    "(SDK ${device.sdkInt}) · ${device.abis.joinToString(", ")}"
        )
        appendLine("Connection: ${header.connection}")
        appendLine("Routing: ${header.routing}")
        appendLine(
            "Lines: ${entries.size} (" +
                    LogLevel.entries.joinToString(", ") { "${it.name} ${counts.getValue(it)}" } + ")"
        )
        appendLine(
            "Dropped: DEBUG ${header.dropped.debug}, INFO and above ${header.dropped.important}"
        )
        if (header.addressesHidden) appendLine("Addresses hidden")
        appendLine()
        var day: LocalDate? = null
        entries.forEach { entry ->
            val time = Instant.ofEpochMilli(entry.timestampMillis).atZone(zone)
            val entryDay = time.toLocalDate()
            if (entryDay != day) {
                appendLine("-- ${DAY_FORMAT.format(entryDay)} --")
                day = entryDay
            }
            append(TIME_FORMAT.format(time))
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

private val EXPORTED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.US)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
