package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddPresetUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddRouteSourceUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.RefreshRouteSourcesUseCase

internal class RoutingExecutor(
    private val routingRepository: RoutingRepository,
    private val addSource: AddRouteSourceUseCase,
    private val addPreset: AddPresetUseCase,
    private val refreshAll: RefreshRouteSourcesUseCase,
) : CoroutineExecutor<RoutingStore.Intent, Action, RoutingStore.State, Msg, Nothing>() {

    override fun executeAction(action: Action) {
        when (action) {
            is Action.ConfigChanged -> dispatch(Msg.ConfigChanged(action.config))
        }
    }

    override fun executeIntent(intent: RoutingStore.Intent) {
        when (intent) {
            is RoutingStore.Intent.SetBypassLan -> scope.launch {
                routingRepository.setBypassLan(intent.enabled)
            }
            is RoutingStore.Intent.SetIpv6Mode -> scope.launch {
                routingRepository.setIpv6Mode(intent.mode)
            }
            is RoutingStore.Intent.SetDnsMode -> scope.launch {
                routingRepository.setDnsMode(intent.mode)
            }
            is RoutingStore.Intent.SetCustomDns -> scope.launch {
                routingRepository.setCustomDns(intent.servers)
            }
            is RoutingStore.Intent.AddSource -> scope.launch {
                addSource(intent.source)
            }
            is RoutingStore.Intent.RemoveSource -> scope.launch {
                routingRepository.removeSource(intent.id)
            }
            is RoutingStore.Intent.SetSourceEnabled -> scope.launch {
                routingRepository.setSourceEnabled(intent.id, intent.enabled)
            }
            is RoutingStore.Intent.AddPreset -> scope.launch {
                addPreset(intent.presetId)
            }
            RoutingStore.Intent.RefreshAll -> scope.launch {
                dispatch(Msg.RefreshingChanged(true))
                try {
                    refreshAll()
                } finally {
                    dispatch(Msg.RefreshingChanged(false))
                }
            }
        }
    }
}
