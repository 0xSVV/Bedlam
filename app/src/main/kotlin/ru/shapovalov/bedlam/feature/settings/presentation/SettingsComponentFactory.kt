package ru.shapovalov.bedlam.feature.settings.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.appselection.presentation.AppSelectionComponentFactory

@Inject
class SettingsComponentFactory(
    private val appSelectionFactory: AppSelectionComponentFactory,
) {
    fun create(componentContext: ComponentContext): SettingsComponent =
        SettingsComponent(componentContext, appSelectionFactory)
}
