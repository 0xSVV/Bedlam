package ru.shapovalov.bedlam.core.routing.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

@Inject
class AddRouteSourceUseCase(
    private val repo: RoutingRepository,
    private val resolver: DirectRouteResolver,
) {
    /**
     * Persists [source] and triggers an immediate resolution attempt.
     * If an equivalent source (by dedupe key) already exists, returns `false`
     * without touching anything.
     */
    suspend operator fun invoke(source: DirectRouteSource): Boolean {
        if (repo.hasEquivalent(source)) return false
        repo.upsertSource(source)
        val result = resolver.resolve(source)
        result.onSuccess { repo.recordResolution(source.id, it, error = null) }
        result.onFailure { repo.recordResolution(source.id, emptyList(), error = it.message) }
        return true
    }
}
