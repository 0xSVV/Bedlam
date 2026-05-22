package ru.shapovalov.bedlam.core.routing.domain.model

data class ResolvedSource(
    val source: DirectRouteSource,
    val cidrs: List<Cidr>,
    val lastResolvedMillis: Long?,
    val lastError: String?,
) {
    val needsInitialResolve: Boolean get() = lastResolvedMillis == null && cidrs.isEmpty()
}
