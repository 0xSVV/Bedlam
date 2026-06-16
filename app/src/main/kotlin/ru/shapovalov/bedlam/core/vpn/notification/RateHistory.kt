package ru.shapovalov.bedlam.core.vpn.notification

class RateHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val tx = ArrayDeque<Long>()
    private val rx = ArrayDeque<Long>()

    @Synchronized
    fun record(txRate: Long, rxRate: Long) {
        tx.addLast(txRate.coerceAtLeast(0))
        rx.addLast(rxRate.coerceAtLeast(0))
        while (tx.size > capacity) tx.removeFirst()
        while (rx.size > capacity) rx.removeFirst()
    }

    @Synchronized
    fun clear() {
        tx.clear()
        rx.clear()
    }

    @Synchronized
    fun snapshot(): Samples = Samples(tx.toList(), rx.toList())

    class Samples(val tx: List<Long>, val rx: List<Long>) {
        val size: Int get() = tx.size
    }

    companion object {
        const val DEFAULT_CAPACITY = 60
    }
}
