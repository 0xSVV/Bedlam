package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Reducer
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

internal sealed interface Msg {
    data class ConfigChanged(val config: RoutingConfig) : Msg
    data class RefreshingChanged(val isRefreshing: Boolean) : Msg
}

internal object RoutingReducer : Reducer<RoutingStore.State, Msg> {
    override fun RoutingStore.State.reduce(msg: Msg): RoutingStore.State = when (msg) {
        is Msg.ConfigChanged -> copy(config = msg.config)
        is Msg.RefreshingChanged -> copy(isRefreshing = msg.isRefreshing)
    }
}
