package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr

object LanRanges {

    val IPV4: List<Cidr.V4> = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "100.64.0.0/10",
        "224.0.0.0/4",
        "255.255.255.255/32",
    ).map { Cidr.parse(it) as Cidr.V4 }

    val IPV6: List<Cidr.V6> = listOf(
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    ).map { Cidr.parse(it) as Cidr.V6 }
}
