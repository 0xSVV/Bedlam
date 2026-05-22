package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject

@Inject
class RoutingComponentFactory(
    private val storeFactory: RoutingStoreFactory,
) {
    fun create(
        componentContext: ComponentContext,
        onBack: RoutingComponent.OnBack,
    ): RoutingComponent = RoutingComponent(componentContext, storeFactory, onBack)
}
