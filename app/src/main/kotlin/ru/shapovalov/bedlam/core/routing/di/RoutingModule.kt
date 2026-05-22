package ru.shapovalov.bedlam.core.routing.di

import android.os.Build
import me.tatarka.inject.annotations.Provides
import ru.shapovalov.bedlam.core.routing.data.RoutingRepositoryImpl
import ru.shapovalov.bedlam.core.routing.data.resolver.DirectRouteResolverImpl
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.bedlam.core.routing.engine.RoutePlanner
import ru.shapovalov.bedlam.di.AppScope
import ru.shapovalov.hysteria.api.TunConfig

interface RoutingModule {

    @AppScope
    @Provides
    fun bindRoutingRepository(impl: RoutingRepositoryImpl): RoutingRepository = impl

    @AppScope
    @Provides
    fun bindDirectRouteResolver(impl: DirectRouteResolverImpl): DirectRouteResolver = impl

    @AppScope
    @Provides
    fun provideRoutePlanner(): RoutePlanner = RoutePlanner(
        supportsExcludeRoute = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        tunPrefixV4 = Cidr.parse(TunConfig.DEFAULT_IPV4_PREFIX) as Cidr.V4,
        tunPrefixV6 = Cidr.parse(TunConfig.DEFAULT_IPV6_PREFIX) as Cidr.V6,
    )
}
