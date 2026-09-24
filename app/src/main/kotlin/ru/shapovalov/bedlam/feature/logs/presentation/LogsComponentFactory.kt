package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.logs.data.LogExporter

@Inject
class LogsComponentFactory(
    private val storeFactory: LogsStoreFactory,
    private val exporter: LogExporter,
) {
    fun create(componentContext: ComponentContext): LogsComponent =
        LogsComponent(componentContext, storeFactory, exporter)
}
