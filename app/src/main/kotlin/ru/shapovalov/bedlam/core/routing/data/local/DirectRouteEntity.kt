package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "direct_route")
data class DirectRouteEntity(
    @PrimaryKey val id: String,
    val cidr: String,
    val comment: String,
    val enabled: Boolean,
    val orderIndex: Int,
)
