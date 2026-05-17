package ru.shapovalov.bedlam.core.profile.domain.model

import ru.shapovalov.hysteria.config.HysteriaConfig
import java.util.UUID

data class Profile(
    val id: String,
    val name: String,
    val config: HysteriaConfig,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun new(name: String, config: HysteriaConfig, now: Long): Profile = Profile(
            id = UUID.randomUUID().toString(),
            name = name,
            config = config,
            createdAt = now,
            updatedAt = now,
        )
    }
}
