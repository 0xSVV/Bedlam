package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.hysteria.api.HysteriaClient

class LogsComponent(
    componentContext: ComponentContext,
    storeFactory: LogsStoreFactory,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state: StateFlow<LogsStore.State> = store.stateFlow(scope)

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    fun onChangeMinLevel(level: HysteriaClient.LogLevel) =
        store.accept(LogsStore.Intent.ChangeMinLevel(level))

    fun onTogglePause() = store.accept(LogsStore.Intent.TogglePaused)
    fun onClear() = store.accept(LogsStore.Intent.Clear)
}
