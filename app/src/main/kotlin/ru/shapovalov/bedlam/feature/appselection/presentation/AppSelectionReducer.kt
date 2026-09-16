package ru.shapovalov.bedlam.feature.appselection.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.appfilter.domain.model.InstalledApp

internal sealed interface Msg {
    data class FilterLoaded(val mode: AppFilterMode, val packages: Set<String>) : Msg
    data class AppsLoaded(val apps: List<InstalledApp>) : Msg
    data class QueryChanged(val query: String) : Msg
}

internal object AppSelectionReducer : Reducer<AppSelectionStore.State, Msg> {
    override fun AppSelectionStore.State.reduce(msg: Msg): AppSelectionStore.State = when (msg) {
        is Msg.FilterLoaded -> {
            val updated = copy(
                mode = msg.mode,
                selectedPackages = msg.packages,
                isFilterLoaded = true,
            )
            if (!isFilterLoaded && isAppsLoaded) updated.withSelectedFirst() else updated
        }
        is Msg.AppsLoaded -> {
            val updated = copy(apps = msg.apps, isAppsLoaded = true)
            if (isFilterLoaded) updated.withSelectedFirst() else updated.withFilteredApps()
        }
        is Msg.QueryChanged -> copy(query = msg.query).withFilteredApps()
    }
}

private fun AppSelectionStore.State.withSelectedFirst(): AppSelectionStore.State =
    copy(apps = apps.sortedBy { it.packageName !in selectedPackages }).withFilteredApps()

private fun AppSelectionStore.State.withFilteredApps(): AppSelectionStore.State = copy(
    filteredApps = if (query.isBlank()) apps else apps.filter { it.matches(query) },
)

private fun InstalledApp.matches(query: String): Boolean =
    label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)
