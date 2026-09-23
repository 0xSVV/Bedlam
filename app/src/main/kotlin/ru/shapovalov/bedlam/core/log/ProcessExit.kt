package ru.shapovalov.bedlam.core.log

import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ProcessExitReason(val code: Int) {
    UNKNOWN(0),
    EXIT_SELF(1),
    SIGNALED(2),
    LOW_MEMORY(3),
    CRASH(4),
    CRASH_NATIVE(5),
    ANR(6),
    INITIALIZATION_FAILURE(7),
    PERMISSION_CHANGE(8),
    EXCESSIVE_RESOURCE_USAGE(9),
    USER_REQUESTED(10),
    USER_STOPPED(11),
    DEPENDENCY_DIED(12),
    OTHER(13),
    FREEZER(14),
    PACKAGE_STATE_CHANGE(15),
    PACKAGE_UPDATED(16);

    companion object {
        fun of(code: Int): ProcessExitReason? = entries.firstOrNull { it.code == code }
    }
}

fun processExitReasonLabel(code: Int): String = ProcessExitReason.of(code)?.name ?: "REASON_$code"

fun processExitLevel(code: Int, importance: Int): LogLevel {
    val level = when (ProcessExitReason.of(code)) {
        ProcessExitReason.CRASH,
        ProcessExitReason.CRASH_NATIVE,
        ProcessExitReason.ANR -> LogLevel.ERROR

        ProcessExitReason.USER_REQUESTED,
        ProcessExitReason.EXIT_SELF,
        ProcessExitReason.USER_STOPPED,
        ProcessExitReason.PACKAGE_STATE_CHANGE,
        ProcessExitReason.PACKAGE_UPDATED ->
            if (importance >= IMPORTANCE_CACHED) LogLevel.DEBUG else LogLevel.INFO

        else -> LogLevel.WARN
    }
    return if (importance <= IMPORTANCE_FOREGROUND_SERVICE) maxOf(level, LogLevel.WARN) else level
}

fun processExitMessage(
    code: Int,
    importance: Int,
    description: String?,
    timestampMillis: Long,
    zone: ZoneId,
): String {
    val at = EXIT_TIME_FORMAT.format(Instant.ofEpochMilli(timestampMillis).atZone(zone))
    return "Last process exit at $at: ${processExitReasonLabel(code)}, importance $importance" +
            description?.let { ", $it" }.orEmpty()
}

private const val IMPORTANCE_CACHED = 400
private const val IMPORTANCE_FOREGROUND_SERVICE = 125

private val EXIT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
