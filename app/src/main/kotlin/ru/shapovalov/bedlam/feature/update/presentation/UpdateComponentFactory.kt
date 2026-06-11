package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.decompose.ComponentContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate

@Inject
class UpdateComponentFactory(
    private val storeFactory: UpdateStoreFactory,
) {
    fun create(
        componentContext: ComponentContext,
        update: AppUpdate,
        onDismiss: UpdateComponent.OnDismiss,
    ): UpdateComponent = UpdateComponent(componentContext, storeFactory, update, onDismiss)
}
