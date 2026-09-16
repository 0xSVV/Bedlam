package ru.shapovalov.bedlam.core.util

fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

sealed interface RelativeAge {
    data object JustNow : RelativeAge
    data class Minutes(val value: Int) : RelativeAge
    data class Hours(val value: Int) : RelativeAge
    data class Days(val value: Int) : RelativeAge
}

fun relativeAge(deltaMillis: Long): RelativeAge {
    val minutes = deltaMillis / 60_000
    return when {
        minutes < 1 -> RelativeAge.JustNow
        minutes < 60 -> RelativeAge.Minutes(minutes.toInt())
        minutes < 24 * 60 -> RelativeAge.Hours((minutes / 60).toInt())
        else -> RelativeAge.Days((minutes / 60 / 24).toInt())
    }
}
