package ru.shapovalov.hysteria.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.shapovalov.hysteria.api.DnsUpstream
import ru.shapovalov.hysteria.api.TunConfig

@Serializable
internal data class WireDnsUpstream(
    @SerialName("transport") val transport: String,
    @SerialName("servers") val servers: List<String>,
    @SerialName("listen") val listen: List<String>,
)

internal fun DnsUpstream.toWireJson(): String = Json.encodeToString(
    WireDnsUpstream.serializer(),
    WireDnsUpstream(
        transport = transport.wire,
        servers = servers,
        listen = listOf(TunConfig.IPV4_DNS_ADDRESS, TunConfig.IPV6_DNS_ADDRESS),
    ),
)
