package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject

@Inject
class SessionComponentFactory(
    private val storeFactory: SessionStoreFactory,
) {
    fun create(
        componentContext: ComponentContext,
        onBack: SessionComponent.OnBack,
    ): SessionComponent = SessionComponent(componentContext, storeFactory, onBack)
}
