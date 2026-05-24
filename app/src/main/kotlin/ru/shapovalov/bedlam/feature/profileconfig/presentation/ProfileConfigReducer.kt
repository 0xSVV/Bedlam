package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.hysteria.config.HysteriaConfig

internal sealed interface Msg {
    data class ProfileLoaded(val profile: Profile) : Msg
    data object ProfileMissing : Msg
    data object EditModeEntered : Msg
    data object ChangesDiscarded : Msg
    data class DraftUpdated(val config: HysteriaConfig) : Msg
    data object SaveStarted : Msg
    data class SaveSucceeded(val profile: Profile) : Msg
    data class SaveFailed(val message: String) : Msg
    data object DeleteRequested : Msg
    data object DeleteCancelled : Msg
    data object DeleteStarted : Msg
    data class DeleteFailed(val message: String) : Msg
    data object ErrorDismissed : Msg
}

internal object ProfileConfigReducer : Reducer<ProfileConfigStore.State, Msg> {
    override fun ProfileConfigStore.State.reduce(msg: Msg): ProfileConfigStore.State = when (msg) {
        is Msg.ProfileLoaded -> copy(
            original = msg.profile,
            draft = draft ?: msg.profile.config,
            isLoading = false,
            notFound = false,
        )
        Msg.ProfileMissing -> copy(isLoading = false, notFound = true)
        Msg.EditModeEntered -> copy(editMode = true, saveError = null)
        Msg.ChangesDiscarded -> copy(
            draft = original?.config,
            editMode = false,
            saveError = null,
        )
        is Msg.DraftUpdated -> copy(draft = msg.config)
        Msg.SaveStarted -> copy(isSaving = true, saveError = null)
        is Msg.SaveSucceeded -> copy(
            original = msg.profile,
            draft = msg.profile.config,
            editMode = false,
            isSaving = false,
            saveError = null,
        )
        is Msg.SaveFailed -> copy(isSaving = false, saveError = msg.message)
        Msg.DeleteRequested -> copy(pendingDeleteConfirmation = true)
        Msg.DeleteCancelled -> copy(pendingDeleteConfirmation = false)
        Msg.DeleteStarted -> copy(pendingDeleteConfirmation = false, isDeleting = true)
        is Msg.DeleteFailed -> copy(isDeleting = false, saveError = msg.message)
        Msg.ErrorDismissed -> copy(saveError = null)
    }
}
