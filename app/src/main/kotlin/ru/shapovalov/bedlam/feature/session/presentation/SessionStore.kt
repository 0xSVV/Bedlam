package ru.shapovalov.bedlam.feature.session.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo

interface SessionStore : Store<SessionStore.Intent, SessionStore.State, Nothing> {

    sealed interface Intent {
        data object Refresh : Intent
    }

    data class State(
        val info: SessionInfo? = null,
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
    )
}
