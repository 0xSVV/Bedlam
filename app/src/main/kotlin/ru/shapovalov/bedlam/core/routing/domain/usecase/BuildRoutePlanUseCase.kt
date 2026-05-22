package ru.shapovalov.bedlam.core.routing.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.bedlam.core.routing.engine.RoutePlanner

@Inject
class BuildRoutePlanUseCase(
    private val routingRepository: RoutingRepository,
    private val appFilterRepository: AppFilterRepository,
    private val planner: RoutePlanner,
) {
    suspend operator fun invoke(): RoutePlan =
        planner.plan(routingRepository.get(), appFilterRepository.get())
}
