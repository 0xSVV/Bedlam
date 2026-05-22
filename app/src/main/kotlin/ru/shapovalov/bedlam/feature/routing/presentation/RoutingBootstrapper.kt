package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineBootstrapper
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

internal sealed interface Action {
    data class ConfigChanged(val config: RoutingConfig) : Action
}

internal class RoutingBootstrapper(
    private val routingRepository: RoutingRepository,
) : CoroutineBootstrapper<Action>() {

    override fun invoke() {
        scope.launch {
            routingRepository.observe().collect { dispatch(Action.ConfigChanged(it)) }
        }
    }
}
