package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class ObfuscationOptions(
    val obfuscationType: String,
    val obfuscationPassword: String,
    /** Gecko only; 0 means the core default (512). */
    val geckoMinPacketSize: Int = 0,
    /** Gecko only; 0 means the core default (1200). */
    val geckoMaxPacketSize: Int = 0,
)
