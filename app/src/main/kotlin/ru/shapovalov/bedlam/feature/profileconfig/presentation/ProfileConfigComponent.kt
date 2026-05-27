package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.util.componentScope
import ru.shapovalov.hysteria.config.HysteriaConfig

class ProfileConfigComponent(
    componentContext: ComponentContext,
    profileId: String,
    storeFactory: ProfileConfigStoreFactory,
    private val onBack: OnBack,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create(profileId) }
    private val scope = componentScope()

    val state: StateFlow<ProfileConfigStore.State> = store.stateFlow(scope)

    fun onEnterEditMode() = store.accept(ProfileConfigStore.Intent.EnterEditMode)
    fun onDiscardChanges() = store.accept(ProfileConfigStore.Intent.DiscardChanges)
    fun onDraftChanged(config: HysteriaConfig) = store.accept(ProfileConfigStore.Intent.UpdateDraft(config))
    fun onSave() = store.accept(ProfileConfigStore.Intent.Save)
    fun onRequestDelete() = store.accept(ProfileConfigStore.Intent.RequestDelete)
    fun onCancelDelete() = store.accept(ProfileConfigStore.Intent.CancelDelete)
    fun onConfirmDelete() = store.accept(ProfileConfigStore.Intent.ConfirmDelete)
    fun onDismissError() = store.accept(ProfileConfigStore.Intent.DismissError)
    fun onBackPressed() = onBack.invoke()

    fun interface OnBack { fun invoke() }
}
