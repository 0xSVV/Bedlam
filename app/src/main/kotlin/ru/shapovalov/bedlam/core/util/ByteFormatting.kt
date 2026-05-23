package ru.shapovalov.bedlam.core.util

import android.content.Context
import android.text.format.Formatter
import ru.shapovalov.bedlam.R

fun Context.formatBytes(bytes: Long): String =
    Formatter.formatShortFileSize(this, bytes)

fun Context.formatRate(bytesPerSec: Long): String =
    getString(R.string.rate_per_second, formatBytes(bytesPerSec))
