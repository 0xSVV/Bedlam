package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnPause
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.util.componentScope
import ru.shapovalov.bedlam.feature.logs.data.LogExportDevice
import ru.shapovalov.bedlam.feature.logs.data.LogExporter
import ru.shapovalov.hysteria.api.HysteriaClient
import java.time.ZonedDateTime

class LogsComponent(
    componentContext: ComponentContext,
    storeFactory: LogsStoreFactory,
    private val exporter: LogExporter,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<LogsStore.State> = store.stateFlow(scope)

    init {
        lifecycle.doOnResume { store.accept(LogsStore.Intent.SetForeground(true)) }
        lifecycle.doOnPause { store.accept(LogsStore.Intent.SetForeground(false)) }
    }

    fun onChangeMinLevel(level: HysteriaClient.LogLevel) =
        store.accept(LogsStore.Intent.ChangeMinLevel(level))

    fun onTogglePause() = store.accept(LogsStore.Intent.TogglePaused)
    fun onClear() = store.accept(LogsStore.Intent.Clear)

    suspend fun exportLog(device: LogExportDevice, exportedAt: ZonedDateTime): String =
        exporter.export(device, exportedAt)
}
