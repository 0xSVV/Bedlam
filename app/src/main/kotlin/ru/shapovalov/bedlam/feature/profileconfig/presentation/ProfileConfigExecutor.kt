package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.usecase.DeleteProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.hysteria.api.HysteriaClient

internal class ProfileConfigExecutor(
    private val saveProfile: SaveProfileUseCase,
    private val deleteProfile: DeleteProfileUseCase,
    private val client: HysteriaClient,
    private val tunnelUsesProfile: suspend (String) -> Boolean,
    private val reconnectProfile: suspend (String) -> Unit,
) : CoroutineExecutor<ProfileConfigStore.Intent, Action, ProfileConfigStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.ProfileLoaded -> dispatch(Msg.ProfileLoaded(action.profile))
            Action.ProfileMissing -> dispatch(Msg.ProfileMissing)
        }
    }

    override fun executeIntent(intent: ProfileConfigStore.Intent) {
        when (intent) {
            ProfileConfigStore.Intent.EnterEditMode -> dispatch(Msg.EditModeEntered)
            ProfileConfigStore.Intent.LeaveEditMode -> leaveEditMode()
            ProfileConfigStore.Intent.CancelDiscard -> dispatch(Msg.DiscardCancelled)
            ProfileConfigStore.Intent.DiscardChanges -> dispatch(Msg.ChangesDiscarded)
            is ProfileConfigStore.Intent.UpdateDraft -> dispatch(Msg.DraftUpdated(intent.config))
            is ProfileConfigStore.Intent.UpdateDraftName -> dispatch(Msg.DraftNameUpdated(intent.name))
            ProfileConfigStore.Intent.Save -> save()
            ProfileConfigStore.Intent.RequestDelete -> dispatch(Msg.DeleteRequested)
            ProfileConfigStore.Intent.CancelDelete -> dispatch(Msg.DeleteCancelled)
            ProfileConfigStore.Intent.ConfirmDelete -> delete()
            ProfileConfigStore.Intent.DismissError -> dispatch(Msg.ErrorDismissed)
            ProfileConfigStore.Intent.DismissReconnectOffer -> dispatch(Msg.ReconnectOfferDismissed)
            ProfileConfigStore.Intent.Reconnect -> reconnect()
        }
    }

    private fun leaveEditMode() {
        val s = state()
        if (!s.editMode || s.isSaving) return
        dispatch(if (s.isDirty) Msg.DiscardRequested else Msg.ChangesDiscarded)
    }

    private fun save() {
        val s = state()
        val draft = s.draft ?: return
        val original = s.original ?: return
        if (s.isSaving) return
        val name = s.draftName?.trim()
        if (name.isNullOrEmpty()) {
            dispatch(Msg.SaveFailed("name must not be empty"))
            return
        }

        client.validateConfig(draft).fold(
            onSuccess = {
                dispatch(Msg.SaveStarted)
                scope.launch {
                    runCatching { saveProfile(original.copy(name = name, config = draft)) }
                        .onSuccess { saved ->
                            val offerReconnect = draft != original.config &&
                                runCatching { tunnelUsesProfile(saved.id) }.getOrDefault(false)
                            dispatch(Msg.SaveSucceeded(saved, offerReconnect))
                        }
                        .onFailure { dispatch(Msg.SaveFailed(it.message ?: "unknown error")) }
                }
            },
            onFailure = { dispatch(Msg.SaveFailed(it.message ?: "invalid configuration")) },
        )
    }

    private fun reconnect() {
        val id = state().original?.id ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { reconnectProfile(id) }
        }
    }

    private fun delete() {
        val s = state()
        val original = s.original ?: return
        if (s.isDeleting) return
        dispatch(Msg.DeleteStarted)
        scope.launch {
            runCatching { deleteProfile(original.id) }
                .onFailure { dispatch(Msg.DeleteFailed(it.message ?: "delete failed")) }
        }
    }
}
