package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "route_source")
data class RouteSourceEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val rawValue: String,
    val comment: String,
    val enabled: Boolean,
    val orderIndex: Int,
    val lastResolvedMillis: Long?,
    val lastError: String?,
) {
    companion object {
        const val KIND_CIDR = "CIDR"
        const val KIND_ASN = "ASN"
        const val KIND_DOMAIN = "DOMAIN"
    }
}
