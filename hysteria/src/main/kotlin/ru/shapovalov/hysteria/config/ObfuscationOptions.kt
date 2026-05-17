package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class ObfuscationOptions(
    val obfuscationType: String,
    val obfuscationPassword: String,
)
