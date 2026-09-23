package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

data class DroppedLines(
    val debug: Long = 0L,
    val info: Long = 0L,
    val warn: Long = 0L,
    val error: Long = 0L,
) {
    val important: Long get() = info + warn + error

    fun atLeast(level: LogLevel): Long = when (level) {
        LogLevel.DEBUG -> debug + important
        LogLevel.INFO -> important
        LogLevel.WARN -> warn + error
        LogLevel.ERROR -> error
    }

    fun plusOne(level: LogLevel): DroppedLines = when (level) {
        LogLevel.DEBUG -> copy(debug = debug + 1)
        LogLevel.INFO -> copy(info = info + 1)
        LogLevel.WARN -> copy(warn = warn + 1)
        LogLevel.ERROR -> copy(error = error + 1)
    }
}

class TieredLogRing(importantCapacity: Int, debugCapacity: Int) {

    private class Arrival(val order: Long, val entry: LogEntry)

    private val important = LogRing<Arrival>(importantCapacity)
    private val debug = LogRing<Arrival>(debugCapacity)
    private var nextOrder = 0L

    var dropped: DroppedLines = DroppedLines()
        private set

    val removedCount: Long get() = important.firstIndex + debug.firstIndex

    fun add(entry: LogEntry) {
        val tier = if (entry.level == LogLevel.DEBUG) debug else important
        val evicted = tier.add(Arrival(nextOrder++, entry)) ?: return
        dropped = dropped.plusOne(evicted.entry.level)
    }

    fun clear() {
        important.clear()
        debug.clear()
        dropped = DroppedLines()
    }

    fun snapshot(): List<LogEntry> {
        val first = important.snapshot()
        val second = debug.snapshot()
        val merged = ArrayList<LogEntry>(first.size + second.size)
        var i = 0
        var j = 0
        while (i < first.size || j < second.size) {
            val takeFirst = j == second.size || (i < first.size && first[i].order < second[j].order)
            merged += if (takeFirst) first[i++].entry else second[j++].entry
        }
        return merged
    }
}
