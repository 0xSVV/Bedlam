package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.extensions.coroutines.stateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteRule
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode

class RoutingComponent(
    componentContext: ComponentContext,
    storeFactory: RoutingStoreFactory,
    private val onBack: OnBack,
) : ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state: StateFlow<RoutingStore.State> = store.stateFlow(scope)

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    fun onBackPressed() = onBack.invoke()
    fun onSetBypassLan(v: Boolean) = store.accept(RoutingStore.Intent.SetBypassLan(v))
    fun onSetIpv6Mode(m: Ipv6Mode) = store.accept(RoutingStore.Intent.SetIpv6Mode(m))
    fun onSetDnsMode(m: DnsMode) = store.accept(RoutingStore.Intent.SetDnsMode(m))
    fun onSetCustomDns(servers: List<String>) = store.accept(RoutingStore.Intent.SetCustomDns(servers))
    fun onUpsertDirectRoute(rule: DirectRouteRule) = store.accept(RoutingStore.Intent.UpsertDirectRoute(rule))
    fun onRemoveDirectRoute(id: String) = store.accept(RoutingStore.Intent.RemoveDirectRoute(id))
    fun onSetDirectRouteEnabled(id: String, enabled: Boolean) =
        store.accept(RoutingStore.Intent.SetDirectRouteEnabled(id, enabled))
    fun onToggleGeoCountry(c: CountryCode) = store.accept(RoutingStore.Intent.ToggleGeoCountry(c))
    fun onDownloadGeoIp() = store.accept(RoutingStore.Intent.DownloadGeoIp)
    fun onRemoveGeoIp() = store.accept(RoutingStore.Intent.RemoveGeoIp)

    fun interface OnBack { fun invoke() }
}
