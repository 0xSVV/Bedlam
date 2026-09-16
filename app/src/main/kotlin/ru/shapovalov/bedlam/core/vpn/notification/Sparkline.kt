package ru.shapovalov.bedlam.core.vpn.notification

import kotlin.math.sqrt

object Sparkline {

    const val PEAK_FLOOR_BYTES_PER_SECOND = 64L * 1024

    fun peak(values: List<Long>): Long =
        maxOf(values.maxOrNull() ?: 0L, PEAK_FLOOR_BYTES_PER_SECOND)

    fun fractions(values: List<Long>, peak: Long): List<Float> {
        if (peak <= 0L) return values.map { 0f }
        return values.map { sqrt((it.toDouble() / peak).coerceIn(0.0, 1.0)).toFloat() }
    }
}
