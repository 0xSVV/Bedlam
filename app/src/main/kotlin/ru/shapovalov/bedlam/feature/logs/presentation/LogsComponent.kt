package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.util.componentScope
import ru.shapovalov.hysteria.api.HysteriaClient

class LogsComponent(
    componentContext: ComponentContext,
    storeFactory: LogsStoreFactory,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<LogsStore.State> = store.stateFlow(scope)

    fun onChangeMinLevel(level: HysteriaClient.LogLevel) =
        store.accept(LogsStore.Intent.ChangeMinLevel(level))

    fun onTogglePause() = store.accept(LogsStore.Intent.TogglePaused)
    fun onClear() = store.accept(LogsStore.Intent.Clear)
}
