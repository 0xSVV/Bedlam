package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter

data class RoutePlan(
    val claimedV4: List<Cidr.V4>,
    val claimedV6: List<Cidr.V6>,
    val excludedV4: List<Cidr.V4>,
    val excludedV6: List<Cidr.V6>,
    val dnsServers: List<String>,
    val appFilter: AppFilter,
)
