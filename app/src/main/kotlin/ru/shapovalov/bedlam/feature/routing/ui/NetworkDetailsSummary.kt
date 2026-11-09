package ru.shapovalov.bedlam.feature.routing.ui

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr

internal data class NetworkDetailsSummary(
    val ipv4Count: Int,
    val ipv6Count: Int,
    val visibleCidrs: List<String>,
    val overflowCount: Int,
)

internal fun summarizeNetworks(cidrs: List<Cidr>): NetworkDetailsSummary {
    var ipv4 = 0
    for (cidr in cidrs) if (cidr is Cidr.V4) ipv4++
    return NetworkDetailsSummary(
        ipv4Count = ipv4,
        ipv6Count = cidrs.size - ipv4,
        visibleCidrs = cidrs.take(MaxVisibleCidrs).map(Cidr::asString),
        overflowCount = (cidrs.size - MaxVisibleCidrs).coerceAtLeast(0),
    )
}

private const val MaxVisibleCidrs = 200
