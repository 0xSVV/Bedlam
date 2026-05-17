package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class ServerCredentials(
    val server: String,
    val auth: String,
)
