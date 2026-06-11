package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.util.componentScope
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate

class UpdateComponent(
    componentContext: ComponentContext,
    storeFactory: UpdateStoreFactory,
    update: AppUpdate,
    private val onDismiss: OnDismiss,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create(update) }
    private val scope = componentScope()

    val state: StateFlow<UpdateStore.State> = store.stateFlow(scope)

    init {
        scope.launch {
            store.labels.collect { label ->
                when (label) {
                    UpdateStore.Label.Dismiss -> onDismiss.invoke()
                }
            }
        }
    }

    fun onInstall() = store.accept(UpdateStore.Intent.Install)
    fun onSkip() = store.accept(UpdateStore.Intent.Skip)

    fun interface OnDismiss {
        fun invoke()
    }
}
