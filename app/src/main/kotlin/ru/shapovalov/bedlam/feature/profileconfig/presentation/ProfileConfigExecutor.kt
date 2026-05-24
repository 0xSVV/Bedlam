package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.profile.domain.usecase.SaveProfileUseCase
import ru.shapovalov.hysteria.api.HysteriaClient

internal class ProfileConfigExecutor(
    private val saveProfile: SaveProfileUseCase,
    private val client: HysteriaClient,
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
            ProfileConfigStore.Intent.DiscardChanges -> dispatch(Msg.ChangesDiscarded)
            is ProfileConfigStore.Intent.UpdateDraft -> dispatch(Msg.DraftUpdated(intent.config))
            ProfileConfigStore.Intent.Save -> save()
            ProfileConfigStore.Intent.DismissError -> dispatch(Msg.ErrorDismissed)
        }
    }

    private fun save() {
        val s = state()
        val draft = s.draft ?: return
        val original = s.original ?: return
        if (s.isSaving) return

        client.validateConfig(draft).fold(
            onSuccess = {
                dispatch(Msg.SaveStarted)
                scope.launch {
                    runCatching { saveProfile(original.copy(config = draft)) }
                        .onSuccess { dispatch(Msg.SaveSucceeded(it)) }
                        .onFailure { dispatch(Msg.SaveFailed(it.message ?: "unknown error")) }
                }
            },
            onFailure = { dispatch(Msg.SaveFailed(it.message ?: "invalid configuration")) },
        )
    }
}
