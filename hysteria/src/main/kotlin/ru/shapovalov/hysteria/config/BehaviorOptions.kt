package ru.shapovalov.hysteria.config

import kotlinx.serialization.Serializable

@Serializable
data class BehaviorOptions(
    val fastOpen: Boolean,
    val lazy: Boolean,
)
