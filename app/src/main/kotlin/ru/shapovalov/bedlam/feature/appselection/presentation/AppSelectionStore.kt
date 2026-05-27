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
        val query: String = "",
        val isLoading: Boolean = true,
    ) {
        val filteredApps: List<InstalledApp>
            get() {
                val base = if (query.isBlank()) apps
                else apps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                }
                return base.sortedBy { it.packageName !in selectedPackages }
            }
    }
}
