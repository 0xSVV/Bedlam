package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "route_source")
data class RouteSourceEntity(
    @PrimaryKey val id: String,
    val kind: String,            // "CIDR" | "ASN" | "DOMAIN"
    val rawValue: String,        // "10.0.0.0/8" | "13238" | "yandex.ru"
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
