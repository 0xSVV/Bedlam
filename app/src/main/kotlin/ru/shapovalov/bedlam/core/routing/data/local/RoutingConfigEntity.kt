package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routing_config")
data class RoutingConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val bypassLan: Boolean = true,
    val ipv6Mode: String = "Enabled",
    val dnsMode: String = "Cloudflare",
    val customDnsCsv: String = "",
) {
    companion object { const val SINGLETON_ID = 1 }
}
