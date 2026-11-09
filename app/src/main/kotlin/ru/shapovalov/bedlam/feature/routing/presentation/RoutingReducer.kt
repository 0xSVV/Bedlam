package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

internal sealed interface Msg {
    data class ConfigChanged(val config: RoutingConfig) : Msg
    data class RefreshingChanged(val isRefreshing: Boolean) : Msg
    data class SourceRemoved(val source: ResolvedSource) : Msg
    data class RemovalDismissed(val id: String) : Msg
}

internal object RoutingReducer : Reducer<RoutingStore.State, Msg> {
    override fun RoutingStore.State.reduce(msg: Msg): RoutingStore.State = when (msg) {
        is Msg.ConfigChanged -> copy(config = msg.config)
        is Msg.RefreshingChanged -> copy(isRefreshing = msg.isRefreshing)
        is Msg.SourceRemoved -> copy(removedSource = msg.source)
        is Msg.RemovalDismissed -> if (removedSource?.source?.id == msg.id) copy(removedSource = null) else this
    }
}
