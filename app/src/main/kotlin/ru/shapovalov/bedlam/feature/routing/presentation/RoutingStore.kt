package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.core.store.Store
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig

interface RoutingStore : Store<RoutingStore.Intent, RoutingStore.State, Nothing> {

    sealed interface Intent {
        data class SetBypassLan(val enabled: Boolean) : Intent
        data class SetIpv6Mode(val mode: Ipv6Mode) : Intent
        data class SetDnsMode(val mode: DnsMode) : Intent
        data class SetCustomDns(val servers: List<String>) : Intent
        data class AddSource(val source: DirectRouteSource) : Intent
        data class RemoveSource(val id: String) : Intent
        data class SetSourceEnabled(val id: String, val enabled: Boolean) : Intent
        data class AddPreset(val presetId: String) : Intent
        data object RefreshAll : Intent
    }

    data class State(
        val config: RoutingConfig = RoutingConfig(),
        val isRefreshing: Boolean = false,
    )
}
