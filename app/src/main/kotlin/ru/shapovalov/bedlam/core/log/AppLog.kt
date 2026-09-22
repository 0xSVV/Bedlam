package ru.shapovalov.bedlam.core.log

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.util.concurrent.atomic.AtomicLong

@AppScope
class AppLog @Inject constructor() {

    private val entries = MutableSharedFlow<LogEntry>(
        replay = REPLAY,
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val nextSeq = AtomicLong(0L)

    val flow: SharedFlow<LogEntry> = entries

    fun info(source: String, message: String) = add(LogLevel.INFO, source, message)

    fun warn(source: String, message: String) = add(LogLevel.WARN, source, message)

    fun error(source: String, message: String) = add(LogLevel.ERROR, source, message)

    private fun add(level: LogLevel, source: String, message: String) {
        entries.tryEmit(
            LogEntry(
                level = level,
                source = source,
                message = message,
                timestampMillis = System.currentTimeMillis(),
                seq = -nextSeq.incrementAndGet(),
            )
        )
    }

    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_VPN = "vpn"
        private const val REPLAY = 32
        private const val BUFFER = 256
    }
}
