package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class CongestionOptions(
    val congestionType: String,
    val bbrProfile: String,
)
