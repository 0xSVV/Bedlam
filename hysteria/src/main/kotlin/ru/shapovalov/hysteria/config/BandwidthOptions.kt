package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class BandwidthOptions(
    val maxTxMbps: Int,
    val maxRxMbps: Int,
    val disableLossCompensation: Boolean = false,
)
