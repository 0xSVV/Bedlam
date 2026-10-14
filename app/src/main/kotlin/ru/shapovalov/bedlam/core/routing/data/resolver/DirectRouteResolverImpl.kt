package ru.shapovalov.bedlam.core.routing.data.resolver

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver

@Inject
class DirectRouteResolverImpl(
    private val asn: RipestatAsnResolver,
) : DirectRouteResolver {

    override suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>> = when (source) {
        is DirectRouteSource.Cidr -> Result.success(listOf(source.cidr))
        is DirectRouteSource.Asn -> asn.resolve(source.asn)
        // Domain rules are applied to exact DNS questions in native code. A
        // resolved snapshot can age out or come from a different Android
        // network, so it must never become route authority.
        is DirectRouteSource.Domain -> Result.success(emptyList())
    }
}
