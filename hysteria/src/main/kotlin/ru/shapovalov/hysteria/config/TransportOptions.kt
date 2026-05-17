package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class TransportOptions(
    val hopIntervalSec: Int,
    val minHopIntervalSec: Int,
    val maxHopIntervalSec: Int,
)
