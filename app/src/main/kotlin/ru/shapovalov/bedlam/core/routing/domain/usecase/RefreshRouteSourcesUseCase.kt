package ru.shapovalov.bedlam.core.routing.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

@Inject
class RefreshRouteSourcesUseCase(
    private val repo: RoutingRepository,
    private val resolver: DirectRouteResolver,
) {
    /**
     * Re-resolves enabled non-literal sources. CIDR sources are skipped — they
     * have nothing to refresh. Resolution runs in parallel.
     *
     * @param staleAfterMillis if non-null, only sources older than this are refreshed.
     */
    suspend operator fun invoke(staleAfterMillis: Long? = null) {
        val now = System.currentTimeMillis()
        val sources = repo.get().sources
            .filter { it.source.enabled }
            .filter { it.source !is DirectRouteSource.Cidr }
            .filter {
                staleAfterMillis == null ||
                    it.lastResolvedMillis == null ||
                    (now - it.lastResolvedMillis) > staleAfterMillis
            }
        coroutineScope {
            sources.map { resolved ->
                async {
                    val result = resolver.resolve(resolved.source)
                    result.onSuccess {
                        repo.recordResolution(resolved.source.id, it, error = null)
                    }
                    result.onFailure {
                        repo.recordResolution(resolved.source.id, emptyList(), error = it.message)
                    }
                }
            }.forEach { it.await() }
        }
    }
}
