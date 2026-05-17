package ru.shapovalov.bedlam.core.profile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import ru.shapovalov.hysteria.config.HysteriaConfig

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val config: HysteriaConfig,
    val createdAt: Long,
    val updatedAt: Long,
)
