package ru.shapovalov.bedlam.feature.logs.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    )

    private val lock = Any()
    private val ring = LogRing(CAPACITY)
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            client.logs(LogLevel.DEBUG).collect { entry ->
                _snapshot.value = synchronized(lock) {
                    Snapshot(ring.add(entry), ring.droppedCount)
                }
            }
        }
    }

    fun clear() {
        _snapshot.value = synchronized(lock) { Snapshot(ring.clear(), ring.droppedCount) }
    }

    companion object {
        const val CAPACITY = 5000
    }
}
