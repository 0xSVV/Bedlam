package ru.shapovalov.bedlam.core.util

/** A `realm://` / `realm+http://` rendezvous address has no direct host:port. */
fun isRealmAddress(address: String): Boolean =
    address.startsWith("realm://") || address.startsWith("realm+http://")

fun parseHost(address: String): String =
    if (address.startsWith("[")) address.removePrefix("[").substringBefore("]")
    else address.substringBeforeLast(":")

fun parsePort(address: String, default: Int = 443): Int {
    val portPart = address.substringAfterLast(":").substringBefore(",").substringBefore("-")
    return portPart.toIntOrNull()?.takeIf { it in 1..65535 } ?: default
}
