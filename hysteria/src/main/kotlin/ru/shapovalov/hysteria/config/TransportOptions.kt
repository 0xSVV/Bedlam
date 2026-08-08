package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class TransportOptions(
    val hopIntervalSec: Int = 0,
    val minHopIntervalSec: Int = 0,
    val maxHopIntervalSec: Int = 0,
)
