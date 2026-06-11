package ru.shapovalov.bedlam.feature.update.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.feature.update.domain.model.AppUpdate

interface UpdateStore : Store<UpdateStore.Intent, UpdateStore.State, UpdateStore.Label> {

    sealed interface Intent {
        data object Install : Intent
        data object Skip : Intent
    }

    sealed interface Label {
        data object Dismiss : Label
    }

    data class State(
        val update: AppUpdate,
        val currentVersion: String,
        val phase: Phase = Phase.Idle,
    ) {
        sealed interface Phase {
            data object Idle : Phase
            data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : Phase
            data object Installing : Phase
            data class Failed(val message: String) : Phase
        }
    }
}
