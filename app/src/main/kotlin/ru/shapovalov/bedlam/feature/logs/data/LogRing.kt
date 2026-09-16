package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry

class LogRing(private val capacity: Int) {

    private val entries = ArrayDeque<LogEntry>()

    var droppedCount: Long = 0L
        private set

    var firstIndex: Long = 0L
        private set

    fun add(entry: LogEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) {
            entries.removeFirst()
            droppedCount++
            firstIndex++
        }
    }

    fun clear() {
        firstIndex += entries.size
        entries.clear()
        droppedCount = 0L
    }

    fun snapshot(): List<LogEntry> = entries.toList()
}
