package ru.shapovalov.bedlam.testing

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver

class FakeDirectRouteResolver(
    var result: Result<List<Cidr>> = Result.success(emptyList()),
) : DirectRouteResolver {

    override suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>> = result
}
