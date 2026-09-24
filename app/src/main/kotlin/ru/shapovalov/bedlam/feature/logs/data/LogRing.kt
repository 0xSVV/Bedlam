package ru.shapovalov.bedlam.feature.logs.data

class LogRing<T>(private val capacity: Int) {

    private val entries = ArrayDeque<T>()

    var firstIndex: Long = 0L
        private set

    fun add(entry: T): T? {
        entries.addLast(entry)
        if (entries.size <= capacity) return null
        firstIndex++
        return entries.removeFirst()
    }

    fun clear() {
        firstIndex += entries.size
        entries.clear()
    }

    fun snapshot(): List<T> = entries.toList()
}
