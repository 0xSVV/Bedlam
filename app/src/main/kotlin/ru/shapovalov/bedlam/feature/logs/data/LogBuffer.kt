package ru.shapovalov.bedlam.feature.logs.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

@AppScope
@Inject
class LogBuffer(client: HysteriaClient) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    init {
        scope.launch {
            client.logs(LogLevel.DEBUG).collect { entry ->
                _entries.update { current ->
                    val next = current + entry
                    if (next.size > CAPACITY) next.takeLast(CAPACITY) else next
                }
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val CAPACITY = 1000
    }
}
