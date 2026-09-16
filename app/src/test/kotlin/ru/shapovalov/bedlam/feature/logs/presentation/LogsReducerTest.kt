package ru.shapovalov.bedlam.feature.logs.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.shapovalov.bedlam.feature.logs.data.LogRing
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.bedlam.testing.reduceAll
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import kotlin.random.Random

class LogsReducerTest {

    private fun reduce(state: LogsStore.State, vararg messages: Msg): LogsStore.State =
        LogsReducer.reduceAll(state, *messages)

    private fun lines(range: IntRange, level: (Int) -> LogLevel = { LogLevel.INFO }) =
        range.map { logEntry(it, level(it)) }

    private fun List<LogEntry>.seqs(): List<Long> = map { it.seq }

    private fun LogsStore.State.fullFilter(): List<LogEntry> =
        (pausedSnapshot ?: liveEntries).filter { it.level >= minLevel }

    @Test
    fun `live lines are filtered by the minimum level`() {
        val entries = lines(0..2) { listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.ERROR)[it] }

        val state = reduce(LogsStore.State(), Msg.LiveUpdated(entries, 0, 0))

        assertEquals(listOf(1L, 2L), state.visibleEntries.seqs())
    }

    @Test
    fun `only lines added since the last update are filtered`() {
        val live = lines(0..2) { if (it == 1) LogLevel.DEBUG else LogLevel.INFO }
        val sentinel = logEntry(99, LogLevel.ERROR)
        val state = LogsStore.State(liveEntries = live.take(2), visibleEntries = listOf(sentinel))

        val updated = reduce(state, Msg.LiveUpdated(live, 0, 0))

        assertEquals(listOf(sentinel, live[2]), updated.visibleEntries)
    }

    @Test
    fun `evicted lines leave the visible list`() {
        val full = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..99), 0, 0))

        val evicted = reduce(full, Msg.LiveUpdated(lines(50..149), 50, 50))

        assertEquals((50L..149L).toList(), evicted.visibleEntries.seqs())
        assertEquals(50L, evicted.droppedCount)
    }

    @Test
    fun `lines after a clear replace the cleared ones`() {
        val before = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..2), 0, 0))

        val after = reduce(before, Msg.LiveUpdated(lines(3..7), 0, 3))

        assertEquals((3L..7L).toList(), after.visibleEntries.seqs())
    }

    @Test
    fun `a level change refilters every line`() {
        val entries = lines(0..11) { LogLevel.entries[it % LogLevel.entries.size] }
        val live = reduce(LogsStore.State(), Msg.LiveUpdated(entries, 0, 0))

        val warn = reduce(live, Msg.MinLevelChanged(LogLevel.WARN))
        assertEquals(entries.filter { it.level >= LogLevel.WARN }, warn.visibleEntries)

        val debug = reduce(warn, Msg.MinLevelChanged(LogLevel.DEBUG))
        assertEquals(entries, debug.visibleEntries)
    }

    @Test
    fun `paused lines stay while live lines arrive and resuming catches up`() {
        val live = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..4), 0, 0))
        val paused = reduce(live, Msg.Paused(live.liveEntries))

        val updated = reduce(paused, Msg.LiveUpdated(lines(0..9), 0, 0))
        assertSame(paused.visibleEntries, updated.visibleEntries)
        assertEquals((0L..9L).toList(), updated.liveEntries.seqs())

        val resumed = reduce(updated, Msg.Resumed)
        assertEquals((0L..9L).toList(), resumed.visibleEntries.seqs())
    }

    @Test
    fun `the DEBUG level shows the live list itself`() {
        val entries = lines(0..4) { LogLevel.DEBUG }
        val initial = LogsStore.State(minLevel = LogLevel.DEBUG)

        val state = reduce(initial, Msg.LiveUpdated(entries, 0, 0))

        assertSame(entries, state.visibleEntries)
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5])
    fun `visible lines always equal the full filter`(seed: Long) {
        val random = Random(seed)
        val ring = LogRing(capacity = random.nextInt(8, 40))
        var next = 0
        var state = LogsStore.State()
        repeat(200) { step ->
            when (random.nextInt(10)) {
                in 0..5 -> repeat(random.nextInt(0, 50)) {
                    ring.add(logEntry(next++, LogLevel.entries.random(random)))
                }

                6 -> ring.clear()
                7 -> state = reduce(state, Msg.MinLevelChanged(LogLevel.entries.random(random)))
                else -> state = reduce(
                    state,
                    if (state.isPaused) Msg.Resumed else Msg.Paused(state.liveEntries),
                )
            }
            if (random.nextInt(3) > 0) {
                val update = Msg.LiveUpdated(ring.snapshot(), ring.droppedCount, ring.firstIndex)
                state = reduce(state, update)
            }

            assertEquals(state.fullFilter(), state.visibleEntries, "step $step")
        }
    }
}
