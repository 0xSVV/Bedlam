package ru.shapovalov.bedlam.core.routing.data.resolver

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver

@Inject
class DirectRouteResolverImpl(
    private val asn: RipestatAsnResolver,
    private val domain: DnsDomainResolver,
) : DirectRouteResolver {

    override suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>> = when (source) {
        is DirectRouteSource.Cidr -> Result.success(listOf(source.cidr))
        is DirectRouteSource.Asn -> asn.resolve(source.asn)
        is DirectRouteSource.Domain -> domain.resolve(source.hostname)
    }
}
