package ru.shapovalov.hysteria.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerCredentials(
    @SerialName("server") val address: String,
    val auth: String,
)
