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
        is Msg.FilterLoaded -> copy(mode = msg.mode, selectedPackages = msg.packages)
        is Msg.AppsLoaded -> copy(apps = msg.apps, isLoading = false)
        is Msg.QueryChanged -> copy(query = msg.query)
    }
}
