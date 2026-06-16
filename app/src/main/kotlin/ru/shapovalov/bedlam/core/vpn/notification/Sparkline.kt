package ru.shapovalov.bedlam.core.vpn.notification

object Sparkline {

    fun peak(tx: List<Long>, rx: List<Long>): Long =
        maxOf(tx.maxOrNull() ?: 0L, rx.maxOrNull() ?: 0L)

    fun fractions(values: List<Long>, peak: Long): List<Float> {
        if (peak <= 0L) return values.map { 0f }
        return values.map { (it.toFloat() / peak).coerceIn(0f, 1f) }
    }
}
