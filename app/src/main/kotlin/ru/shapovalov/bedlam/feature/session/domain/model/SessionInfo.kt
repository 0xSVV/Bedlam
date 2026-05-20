package ru.shapovalov.bedlam.feature.session.domain.model

data class SessionInfo(
    val ipv4: String?,
    val ipv6: String?,
    val asn: String?,
    val asOrganization: String?,
    val country: String?,
    val city: String?,
    val region: String?,
    val latitude: Double?,
    val longitude: Double?,
)
