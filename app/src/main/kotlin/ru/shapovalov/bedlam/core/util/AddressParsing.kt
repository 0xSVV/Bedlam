package ru.shapovalov.bedlam.core.util

fun parseHost(address: String): String =
    if (address.startsWith("[")) address.removePrefix("[").substringBefore("]")
    else address.substringBeforeLast(":")

fun parsePort(address: String, default: Int = 443): Int {
    val portPart = address.substringAfterLast(":").substringBefore(",").substringBefore("-")
    return portPart.toIntOrNull()?.takeIf { it in 1..65535 } ?: default
}
