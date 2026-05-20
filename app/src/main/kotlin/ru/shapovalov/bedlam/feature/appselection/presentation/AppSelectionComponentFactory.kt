package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject

@Inject
class AppSelectionComponentFactory(
    private val storeFactory: AppSelectionStoreFactory,
) {
    fun create(
        componentContext: ComponentContext,
        onBack: AppSelectionComponent.OnBack,
    ): AppSelectionComponent = AppSelectionComponent(componentContext, storeFactory, onBack)
}
