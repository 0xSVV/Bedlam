package ru.shapovalov.bedlam.core.routing.engine

import ru.shapovalov.bedlam.core.routing.domain.model.Cidr

/** Canonical LAN ranges excluded when bypass-LAN is on. */
object LanRanges {

    val IPV4: List<Cidr.V4> = listOf(
        "10.0.0.0/8",            // RFC1918
        "172.16.0.0/12",         // RFC1918
        "192.168.0.0/16",        // RFC1918
        "169.254.0.0/16",        // link-local
        "100.64.0.0/10",         // CGNAT (RFC6598)
        "127.0.0.0/8",           // loopback
        "224.0.0.0/4",           // multicast
        "255.255.255.255/32",    // broadcast
    ).map { Cidr.parse(it) as Cidr.V4 }

    val IPV6: List<Cidr.V6> = listOf(
        "fc00::/7",              // unique local addresses
        "fe80::/10",             // link-local
        "ff00::/8",              // multicast
        "::1/128",               // loopback
    ).map { Cidr.parse(it) as Cidr.V6 }
}
