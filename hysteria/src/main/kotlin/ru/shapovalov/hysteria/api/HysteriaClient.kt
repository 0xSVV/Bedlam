package ru.shapovalov.hysteria.api

import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaConfig

interface HysteriaClient {
    val state: StateFlow<ConnectionState>
    suspend fun connect(config: HysteriaConfig)
    fun disconnect()
    fun setLogListener(listener: LogListener?)

    interface LogListener {
        fun onLog(level: String, message: String)
    }
}
