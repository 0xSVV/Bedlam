package ru.shapovalov.hysteria.internal

data class HysteriaConfig(
    val server: String,
    val auth: String,
    val tlsSni: String = "",
    val tlsInsecure: Boolean = false,
    val tlsCa: String = "",
    val disablePathMTUDiscovery: Boolean = false,
    val fastOpen: Boolean = false,
    val maxTxMbps: Int = 0,
    val maxRxMbps: Int = 0,
    val socksAddr: String = "127.0.0.1:1080",
    val httpAddr: String = "",
)