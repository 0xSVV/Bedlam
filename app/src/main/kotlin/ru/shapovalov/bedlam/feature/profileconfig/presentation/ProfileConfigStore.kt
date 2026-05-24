package ru.shapovalov.bedlam.feature.profileconfig.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.hysteria.config.HysteriaConfig

interface ProfileConfigStore : Store<ProfileConfigStore.Intent, ProfileConfigStore.State, Nothing> {

    sealed interface Intent {
        data object EnterEditMode : Intent
        data object DiscardChanges : Intent
        data class UpdateDraft(val config: HysteriaConfig) : Intent
        data object Save : Intent
        data object RequestDelete : Intent
        data object CancelDelete : Intent
        data object ConfirmDelete : Intent
        data object DismissError : Intent
    }

    data class State(
        val profileId: String,
        val original: Profile? = null,
        val draft: HysteriaConfig? = null,
        val editMode: Boolean = false,
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val isDeleting: Boolean = false,
        val notFound: Boolean = false,
        val pendingDeleteConfirmation: Boolean = false,
        val saveError: String? = null,
    ) {
        val isDirty: Boolean
            get() = draft != null && original != null && draft != original.config
    }
}
