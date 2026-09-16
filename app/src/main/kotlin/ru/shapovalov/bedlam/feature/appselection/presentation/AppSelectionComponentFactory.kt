package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.data.AppIconLoader

@Inject
class AppSelectionComponentFactory(
    private val storeFactory: AppSelectionStoreFactory,
    private val iconLoader: AppIconLoader,
) {
    fun create(
        componentContext: ComponentContext,
        onBack: AppSelectionComponent.OnBack,
    ): AppSelectionComponent =
        AppSelectionComponent(componentContext, storeFactory, iconLoader, onBack)
}
