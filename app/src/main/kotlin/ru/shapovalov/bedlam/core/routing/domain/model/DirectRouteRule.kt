package ru.shapovalov.bedlam.core.routing.domain.model

data class DirectRouteRule(
    val id: String,
    val cidr: Cidr,
    val comment: String,
    val enabled: Boolean = true,
    val orderIndex: Int = 0,
)
