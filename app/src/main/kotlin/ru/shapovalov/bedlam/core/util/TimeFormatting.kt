package ru.shapovalov.bedlam.core.util

fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
