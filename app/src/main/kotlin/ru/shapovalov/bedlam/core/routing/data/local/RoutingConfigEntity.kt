package ru.shapovalov.bedlam.core.routing.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routing_config")
data class RoutingConfigEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val bypassLan: Boolean = true,
    val ipv6Mode: String = "Enabled",
    val dnsMode: String = "Cloudflare",
    val customDnsCsv: String = "",
    @ColumnInfo(defaultValue = "Tcp")
    val dnsTransport: String = "Tcp",
    @ColumnInfo(defaultValue = "0")
    val mtu: Int = 0,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
