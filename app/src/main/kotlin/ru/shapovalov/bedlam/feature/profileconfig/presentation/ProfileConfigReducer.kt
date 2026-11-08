package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.util.isRealmAddress
import ru.shapovalov.hysteria.config.HysteriaConfig

internal sealed interface Msg {
    data class ProfileLoaded(val profile: Profile) : Msg
    data object ProfileMissing : Msg
    data object EditModeEntered : Msg
    data object DiscardRequested : Msg
    data object DiscardCancelled : Msg
    data object ChangesDiscarded : Msg
    data class DraftUpdated(val config: HysteriaConfig) : Msg
    data class DraftNameUpdated(val name: String) : Msg
    data class NumericFieldValidityChanged(val field: String, val valid: Boolean) : Msg
    data object SaveStarted : Msg
    data class SaveSucceeded(val profile: Profile, val offerReconnect: Boolean) : Msg
    data class SaveFailed(val message: String) : Msg
    data object DeleteRequested : Msg
    data object DeleteCancelled : Msg
    data object DeleteStarted : Msg
    data class DeleteFailed(val message: String) : Msg
    data object ErrorDismissed : Msg
    data object ReconnectOfferDismissed : Msg
}

internal object ProfileConfigReducer : Reducer<ProfileConfigStore.State, Msg> {
    override fun ProfileConfigStore.State.reduce(msg: Msg): ProfileConfigStore.State = when (msg) {
        is Msg.ProfileLoaded -> if (editMode) {
            copy(
                original = msg.profile,
                draft = draft ?: msg.profile.config,
                draftName = draftName ?: msg.profile.name,
                isLoading = false,
                notFound = false,
            )
        } else {
            copy(
                original = msg.profile,
                draft = msg.profile.config,
                draftName = msg.profile.name,
                isLoading = false,
                notFound = false,
            )
        }

        Msg.ProfileMissing -> copy(isLoading = false, notFound = true)
        Msg.EditModeEntered -> copy(editMode = true, saveError = null, offerReconnect = false)
        Msg.DiscardRequested -> copy(pendingDiscardConfirmation = true)
        Msg.DiscardCancelled -> copy(pendingDiscardConfirmation = false)
        Msg.ChangesDiscarded -> copy(
            draft = original?.config,
            draftName = original?.name,
            editMode = false,
            saveError = null,
            pendingDiscardConfirmation = false,
            invalidNumericFields = emptySet(),
        )

        is Msg.DraftUpdated -> copy(
            draft = msg.config,
            invalidNumericFields = invalidNumericFields.filterTo(mutableSetOf()) { msg.config.hasNumericField(it) },
        )
        is Msg.DraftNameUpdated -> copy(draftName = msg.name)
        is Msg.NumericFieldValidityChanged -> copy(
            invalidNumericFields = if (msg.valid || draft?.hasNumericField(msg.field) != true) {
                invalidNumericFields - msg.field
            } else {
                invalidNumericFields + msg.field
            },
        )
        Msg.SaveStarted -> copy(isSaving = true, saveError = null)
        is Msg.SaveSucceeded -> copy(
            original = msg.profile,
            draft = msg.profile.config,
            draftName = msg.profile.name,
            editMode = false,
            isSaving = false,
            saveError = null,
            pendingDiscardConfirmation = false,
            invalidNumericFields = emptySet(),
            offerReconnect = msg.offerReconnect,
        )

        is Msg.SaveFailed -> copy(isSaving = false, saveError = msg.message)
        Msg.DeleteRequested -> copy(pendingDeleteConfirmation = true)
        Msg.DeleteCancelled -> copy(pendingDeleteConfirmation = false)
        Msg.DeleteStarted -> copy(pendingDeleteConfirmation = false, isDeleting = true)
        is Msg.DeleteFailed -> copy(isDeleting = false, saveError = msg.message)
        Msg.ErrorDismissed -> copy(saveError = null)
        Msg.ReconnectOfferDismissed -> copy(offerReconnect = false)
    }
}

private fun HysteriaConfig.hasNumericField(field: String): Boolean = when (field) {
    "geckoMinPacketSize", "geckoMaxPacketSize" -> obfuscation?.obfuscationType.equals("gecko", ignoreCase = true)
    "stunTimeoutMs", "punchTimeoutMs" -> isRealmAddress(server.address) || realm != null
    else -> true
}
