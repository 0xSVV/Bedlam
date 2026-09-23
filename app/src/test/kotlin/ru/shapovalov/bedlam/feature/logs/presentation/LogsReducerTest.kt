package ru.shapovalov.bedlam.feature.logs.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.shapovalov.bedlam.feature.logs.data.DroppedLines
import ru.shapovalov.bedlam.feature.logs.data.TieredLogRing
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

        val state = reduce(LogsStore.State(), Msg.LiveUpdated(entries, DroppedLines(), 0L))

        assertEquals(listOf(1L, 2L), state.visibleEntries.seqs())
    }

    @Test
    fun `only lines added since the last update are filtered`() {
        val live = lines(0..2) { if (it == 1) LogLevel.DEBUG else LogLevel.INFO }
        val sentinel = logEntry(99, LogLevel.ERROR)
        val state = LogsStore.State(liveEntries = live.take(2), visibleEntries = listOf(sentinel))

        val updated = reduce(state, Msg.LiveUpdated(live, DroppedLines(), 0L))

        assertEquals(listOf(sentinel, live[2]), updated.visibleEntries)
    }

    @Test
    fun `evicted lines leave the visible list`() {
        val full = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..99), DroppedLines(), 0L))

        val evicted = reduce(full, Msg.LiveUpdated(lines(50..149), DroppedLines(info = 50L), 50L))

        assertEquals((50L..149L).toList(), evicted.visibleEntries.seqs())
        assertEquals(50L, evicted.droppedCount)
    }

    @Test
    fun `a debug line evicted between kept lines leaves the visible list`() {
        val live = lines(0..3) { if (it % 2 == 0) LogLevel.DEBUG else LogLevel.INFO }
        val debug = reduce(
            LogsStore.State(minLevel = LogLevel.DEBUG),
            Msg.LiveUpdated(live, DroppedLines(), 0L),
        )

        val updated = reduce(
            debug,
            Msg.LiveUpdated(listOf(live[1], live[2], live[3], logEntry(4, LogLevel.DEBUG)), DroppedLines(debug = 1L), 1L),
        )

        assertEquals(listOf(1L, 2L, 3L, 4L), updated.visibleEntries.seqs())
    }

    @Test
    fun `the dropped count covers only the levels the filter shows`() {
        val dropped = DroppedLines(debug = 900L, info = 5L, warn = 2L)
        val live = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..2), dropped, 907L))

        assertEquals(7L, live.droppedCount)
        assertEquals(907L, reduce(live, Msg.MinLevelChanged(LogLevel.DEBUG)).droppedCount)
        assertEquals(2L, reduce(live, Msg.MinLevelChanged(LogLevel.WARN)).droppedCount)
        assertEquals(0L, reduce(live, Msg.MinLevelChanged(LogLevel.ERROR)).droppedCount)
    }

    @Test
    fun `lines after a clear replace the cleared ones`() {
        val before = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..2), DroppedLines(), 0L))

        val after = reduce(before, Msg.LiveUpdated(lines(3..7), DroppedLines(), 3L))

        assertEquals((3L..7L).toList(), after.visibleEntries.seqs())
    }

    @Test
    fun `a level change refilters every line`() {
        val entries = lines(0..11) { LogLevel.entries[it % LogLevel.entries.size] }
        val live = reduce(LogsStore.State(), Msg.LiveUpdated(entries, DroppedLines(), 0L))

        val warn = reduce(live, Msg.MinLevelChanged(LogLevel.WARN))
        assertEquals(entries.filter { it.level >= LogLevel.WARN }, warn.visibleEntries)

        val debug = reduce(warn, Msg.MinLevelChanged(LogLevel.DEBUG))
        assertEquals(entries, debug.visibleEntries)
    }

    @Test
    fun `paused lines stay while live lines arrive and resuming catches up`() {
        val live = reduce(LogsStore.State(), Msg.LiveUpdated(lines(0..4), DroppedLines(), 0L))
        val paused = reduce(live, Msg.Paused(live.liveEntries))

        val updated = reduce(paused, Msg.LiveUpdated(lines(0..9), DroppedLines(), 0L))
        assertSame(paused.visibleEntries, updated.visibleEntries)
        assertEquals((0L..9L).toList(), updated.liveEntries.seqs())

        val resumed = reduce(updated, Msg.Resumed)
        assertEquals((0L..9L).toList(), resumed.visibleEntries.seqs())
    }

    @Test
    fun `the DEBUG level shows the live list itself`() {
        val entries = lines(0..4) { LogLevel.DEBUG }
        val initial = LogsStore.State(minLevel = LogLevel.DEBUG)

        val state = reduce(initial, Msg.LiveUpdated(entries, DroppedLines(), 0L))

        assertSame(entries, state.visibleEntries)
    }

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5])
    fun `visible lines always equal the full filter`(seed: Long) {
        val random = Random(seed)
        val ring = TieredLogRing(
            importantCapacity = random.nextInt(4, 20),
            debugCapacity = random.nextInt(4, 20),
        )
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
                val update = Msg.LiveUpdated(ring.snapshot(), ring.dropped, ring.removedCount)
                state = reduce(state, update)
            }

            assertEquals(state.fullFilter(), state.visibleEntries, "step $step")
        }
    }
}
