package ru.shapovalov.hysteria.internal

data class HysteriaConfig(
    val server: String,
    val auth: String,
    val tlsSni: String = "",
    val tlsInsecure: Boolean = false,
    val disablePathMTUDiscovery: Boolean = false,
    val fastOpen: Boolean = false,
    val socksAddr: String = "127.0.0.1:1080",
    val httpAddr: String = "",
)
