package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class QuicOptions(
    val initStreamReceiveWindow: Long,
    val maxStreamReceiveWindow: Long,
    val initConnReceiveWindow: Long,
    val maxConnReceiveWindow: Long,
    val maxIdleTimeoutSec: Int,
    val keepAlivePeriodSec: Int,
    val disablePathMTUDiscovery: Boolean,
)
