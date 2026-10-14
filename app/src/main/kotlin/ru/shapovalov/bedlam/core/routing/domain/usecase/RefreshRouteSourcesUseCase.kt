package ru.shapovalov.bedlam.core.routing.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

@Inject
class RefreshRouteSourcesUseCase(
    private val repo: RoutingRepository,
    private val resolver: DirectRouteResolver,
) {
    data class Outcome(val attempted: Int, val succeeded: Int, val transientFailures: Int, val permanentFailures: Int)

    suspend operator fun invoke(staleAfterMillis: Long? = null): Outcome {
        val now = System.currentTimeMillis()
        val sources = repo.get().sources
            .filter { it.source.enabled }
            .filter { it.source !is DirectRouteSource.Cidr }
            .filter {
                staleAfterMillis == null ||
                        it.lastResolvedMillis == null ||
                        (now - it.lastResolvedMillis) > staleAfterMillis
            }
        val outcomes = coroutineScope {
            sources.map { resolved ->
                async {
                    val result = try {
                        resolver.resolve(resolved.source)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    result.onSuccess {
                        repo.recordResolution(resolved.source, it, error = null)
                    }
                    result.onFailure { failure ->
                        repo.recordResolutionError(resolved.source, failure.message)
                    }
                    result.exceptionOrNull()
                }
            }.awaitAll()
        }
        return Outcome(
            attempted = sources.size,
            succeeded = outcomes.count { it == null },
            transientFailures = outcomes.count { it?.isTransientRefreshFailure() == true },
            permanentFailures = outcomes.count { it != null && !it.isTransientRefreshFailure() },
        )
    }
}

private fun Throwable.isTransientRefreshFailure(): Boolean {
    val message = message.orEmpty()
    val httpCode = Regex("HTTP (\\d{3})").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (httpCode != null) return httpCode == 408 || httpCode == 429 || httpCode >= 500
    return this is java.io.IOException
}
