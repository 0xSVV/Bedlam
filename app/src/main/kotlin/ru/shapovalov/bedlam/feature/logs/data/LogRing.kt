package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry

class LogRing(private val capacity: Int) {

    private val entries = ArrayDeque<LogEntry>()

    var droppedCount: Long = 0L
        private set

    fun add(entry: LogEntry): List<LogEntry> {
        entries.addLast(entry)
        while (entries.size > capacity) {
            entries.removeFirst()
            droppedCount++
        }
        return entries.toList()
    }

    fun clear(): List<LogEntry> {
        entries.clear()
        droppedCount = 0L
        return emptyList()
    }
}
