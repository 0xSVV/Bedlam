package ru.shapovalov.hysteria.config

data class TransportOptions(
    val hopIntervalSec: Int,
    val minHopIntervalSec: Int,
    val maxHopIntervalSec: Int,
)
