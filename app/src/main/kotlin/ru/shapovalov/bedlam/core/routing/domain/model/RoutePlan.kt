package ru.shapovalov.bedlam.core.routing.domain.model

import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter

/**
 * Output of [RoutePlanner.plan]. On API 33+ both `claimed*` and `excluded*`
 * are populated; on older APIs `excluded*` is empty and `claimed*` is
 * pre-subtracted.
 */
data class RoutePlan(
    val claimedV4: List<Cidr.V4>,
    val claimedV6: List<Cidr.V6>,
    val excludedV4: List<Cidr.V4>,
    val excludedV6: List<Cidr.V6>,
    val dnsServers: List<String>,
    val appFilter: AppFilter,
)
