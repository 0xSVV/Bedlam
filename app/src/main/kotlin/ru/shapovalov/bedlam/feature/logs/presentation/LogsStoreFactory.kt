package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer

@Inject
class LogsStoreFactory(
    private val storeFactory: StoreFactory,
    private val buffer: LogBuffer,
) {
    fun create(): LogsStore =
        object : LogsStore, Store<LogsStore.Intent, LogsStore.State, Nothing>
        by storeFactory.create(
            name = "LogsStore",
            initialState = LogsStore.State(),
            bootstrapper = LogsBootstrapper(buffer),
            executorFactory = { LogsExecutor(buffer) },
            reducer = LogsReducer,
        ) {}
}
