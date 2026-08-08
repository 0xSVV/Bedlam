package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class QuicOptions(
    val initStreamReceiveWindow: Long = 0,
    val maxStreamReceiveWindow: Long = 0,
    val initConnReceiveWindow: Long = 0,
    val maxConnReceiveWindow: Long = 0,
    val maxIdleTimeoutSec: Int = 0,
    val keepAlivePeriodSec: Int = 0,
    val disablePathMTUDiscovery: Boolean = true,
    val disableChromeParrot: Boolean = true,
    val disableGso: Boolean = false,
)
