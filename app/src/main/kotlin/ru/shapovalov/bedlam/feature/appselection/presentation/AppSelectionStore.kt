package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp

interface AppSelectionStore : Store<AppSelectionStore.Intent, AppSelectionStore.State, Nothing> {

    sealed interface Intent {
        data class ChangeMode(val mode: AppFilterMode) : Intent
        data class TogglePackage(val pkg: String) : Intent
        data class UpdateQuery(val query: String) : Intent
    }

    data class State(
        val mode: AppFilterMode = AppFilterMode.All,
        val selectedPackages: Set<String> = emptySet(),
        val apps: List<InstalledApp> = emptyList(),
        val filteredApps: List<InstalledApp> = emptyList(),
        val query: String = "",
        val isFilterLoaded: Boolean = false,
        val isAppsLoaded: Boolean = false,
    ) {
        val isLoading: Boolean get() = !isFilterLoaded || !isAppsLoaded
    }
}
