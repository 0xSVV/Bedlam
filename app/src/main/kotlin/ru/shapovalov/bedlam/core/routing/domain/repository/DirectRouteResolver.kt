package ru.shapovalov.bedlam.core.routing.domain.repository

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource

/** Resolves a single [DirectRouteSource] to the set of CIDRs it currently covers. */
interface DirectRouteResolver {
    suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>>
}
