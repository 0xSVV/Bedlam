package ru.shapovalov.bedlam.feature.logs.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

@AppScope
class LogBuffer internal constructor(
    client: HysteriaClient,
    scope: CoroutineScope,
) {

    @Inject
    constructor(client: HysteriaClient) : this(
        client = client,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    data class Snapshot(
        val entries: List<LogEntry> = emptyList(),
        val droppedCount: Long = 0L,
        val firstIndex: Long = 0L,
    )

    private val lock = Any()
    private val ring = LogRing(CAPACITY)
    private var unpublished = false
    private val lineAdded = Channel<Unit>(Channel.CONFLATED)
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            client.logs(LogLevel.DEBUG).collect { entry ->
                synchronized(lock) {
                    ring.add(entry)
                    unpublished = true
                }
                lineAdded.trySend(Unit)
            }
        }
        scope.launch {
            _snapshot.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { collected -> if (collected) publishAddedLines() }
        }
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            publishLocked()
        }
    }

    private suspend fun publishAddedLines() {
        while (true) {
            if (publishUnpublished()) delay(PUBLISH_INTERVAL_MS)
            lineAdded.receive()
        }
    }

    private fun publishUnpublished(): Boolean = synchronized(lock) {
        val published = unpublished
        if (published) publishLocked()
        published
    }

    private fun publishLocked() {
        unpublished = false
        _snapshot.value = Snapshot(ring.snapshot(), ring.droppedCount, ring.firstIndex)
    }

    companion object {
        const val CAPACITY = 5000
        private const val PUBLISH_INTERVAL_MS = 100L
    }
}
