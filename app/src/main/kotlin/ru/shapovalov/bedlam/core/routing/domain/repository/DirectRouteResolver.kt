package ru.shapovalov.bedlam.core.routing.domain.repository

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource

interface DirectRouteResolver {
    suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>>
}
