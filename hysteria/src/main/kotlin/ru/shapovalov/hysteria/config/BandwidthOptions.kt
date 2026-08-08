package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class BandwidthOptions(
    val maxTxMbps: Int = 0,
    val maxRxMbps: Int = 0,
    val disableLossCompensation: Boolean = false,
)
