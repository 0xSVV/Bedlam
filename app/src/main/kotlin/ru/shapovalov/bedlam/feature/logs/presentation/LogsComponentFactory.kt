package ru.shapovalov.bedlam.feature.logs.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject

@Inject
class LogsComponentFactory(
    private val storeFactory: LogsStoreFactory,
) {
    fun create(componentContext: ComponentContext): LogsComponent =
        LogsComponent(componentContext, storeFactory)
}
