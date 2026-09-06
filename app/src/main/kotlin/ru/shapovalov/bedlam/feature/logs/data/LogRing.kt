package ru.shapovalov.bedlam.feature.logs.data

import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry

class LogRing(private val capacity: Int) {

    private val entries = ArrayDeque<LogEntry>()

    fun add(entry: LogEntry): List<LogEntry> {
        entries.addLast(entry)
        while (entries.size > capacity) {
            entries.removeFirst()
        }
        return entries.toList()
    }

    fun clear(): List<LogEntry> {
        entries.clear()
        return emptyList()
    }
}
