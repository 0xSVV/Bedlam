package ru.shapovalov.bedlam.core.profile.data.mapper

import ru.shapovalov.bedlam.core.profile.data.local.ProfileEntity
import ru.shapovalov.bedlam.core.profile.domain.model.Profile

fun ProfileEntity.toDomain(): Profile = Profile(
    id = id,
    name = name,
    config = config,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Profile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    name = name,
    config = config,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
