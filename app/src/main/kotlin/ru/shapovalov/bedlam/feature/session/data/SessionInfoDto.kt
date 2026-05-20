package ru.shapovalov.bedlam.feature.session.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class IpApiDto(
    val ip: String? = null,
    val version: String? = null,
    val city: String? = null,
    val region: String? = null,
    @SerialName("country") val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val asn: String? = null,
    val org: String? = null,
)

@Serializable
internal data class IpifyDto(val ip: String? = null)
