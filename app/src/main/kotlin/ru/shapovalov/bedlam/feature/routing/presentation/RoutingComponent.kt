package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.util.componentScope
import ru.shapovalov.hysteria.api.DnsTransport

class RoutingComponent(
    componentContext: ComponentContext,
    storeFactory: RoutingStoreFactory,
    private val onBack: OnBack,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = componentScope()

    val state: StateFlow<RoutingStore.State> = store.stateFlow(scope)

    fun onBackPressed() = onBack.invoke()
    fun onSetBypassLan(v: Boolean) = store.accept(RoutingStore.Intent.SetBypassLan(v))
    fun onSetIpv6Mode(m: Ipv6Mode) = store.accept(RoutingStore.Intent.SetIpv6Mode(m))
    fun onSetDnsMode(m: DnsMode) = store.accept(RoutingStore.Intent.SetDnsMode(m))
    fun onSetDnsTransport(t: DnsTransport) = store.accept(RoutingStore.Intent.SetDnsTransport(t))
    fun onSetMtu(mtu: Int) = store.accept(RoutingStore.Intent.SetMtu(mtu))
    fun onSetCustomDns(servers: List<String>) =
        store.accept(RoutingStore.Intent.SetCustomDns(servers))

    fun onAddSource(source: DirectRouteSource) = store.accept(RoutingStore.Intent.AddSource(source))
    fun onRemoveSource(id: String) = store.accept(RoutingStore.Intent.RemoveSource(id))
    fun onSetSourceEnabled(id: String, enabled: Boolean) =
        store.accept(RoutingStore.Intent.SetSourceEnabled(id, enabled))

    fun onAddPreset(presetId: String) = store.accept(RoutingStore.Intent.AddPreset(presetId))
    fun onRefreshAll() = store.accept(RoutingStore.Intent.RefreshAll)

    fun interface OnBack {
        fun invoke()
    }
}
