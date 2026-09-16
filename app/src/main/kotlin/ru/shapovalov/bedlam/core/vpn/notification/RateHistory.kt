package ru.shapovalov.bedlam.core.vpn.notification

class RateHistory(
    private val slots: Int = SLOTS,
    private val slotMillis: Long = SLOT_MILLIS,
) {

    private val buckets = ArrayDeque<Bucket>()

    @Synchronized
    fun record(bytesPerSecond: Long, atMillis: Long) {
        val slot = atMillis / slotMillis
        val value = bytesPerSecond.coerceAtLeast(0)
        val last = buckets.lastOrNull()
        when {
            last == null || slot > last.slot -> buckets.addLast(Bucket(slot, value))
            slot == last.slot -> last.value = maxOf(last.value, value)
        }
        while (buckets.size > slots) buckets.removeFirst()
    }

    @Synchronized
    fun clear() {
        buckets.clear()
    }

    @Synchronized
    fun snapshot(nowMillis: Long): List<Long> {
        val newest = nowMillis / slotMillis
        val oldest = newest - slots + 1
        val bySlot = buckets.associate { it.slot to it.value }
        return (oldest..newest).map { bySlot[it] ?: 0L }
    }

    private class Bucket(val slot: Long, var value: Long)

    companion object {
        const val SLOTS = 30
        const val SLOT_MILLIS = 2_000L
    }
}
