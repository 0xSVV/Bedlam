package ru.shapovalov.bedlam.core.vpn.notification

class RateHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val samples = ArrayDeque<Long>()

    @Synchronized
    fun record(bytesPerSecond: Long) {
        samples.addLast(bytesPerSecond.coerceAtLeast(0))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    @Synchronized
    fun snapshot(): List<Long> = samples.toList()

    companion object {
        const val DEFAULT_CAPACITY = 60
    }
}
