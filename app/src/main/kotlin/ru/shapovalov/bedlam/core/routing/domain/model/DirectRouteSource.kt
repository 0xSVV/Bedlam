package ru.shapovalov.bedlam.core.routing.domain.model

sealed interface DirectRouteSource {
    val id: String
    val comment: String
    val enabled: Boolean
    val orderIndex: Int

    fun label(): String
    fun dedupeKey(): String

    data class Cidr(
        override val id: String,
        val cidr: ru.shapovalov.bedlam.core.routing.domain.model.Cidr,
        override val comment: String,
        override val enabled: Boolean,
        override val orderIndex: Int,
    ) : DirectRouteSource {
        override fun label(): String = cidr.asString()
        override fun dedupeKey(): String = "cidr:${cidr.asString()}"
    }

    data class Asn(
        override val id: String,
        val asn: Int,
        override val comment: String,
        override val enabled: Boolean,
        override val orderIndex: Int,
    ) : DirectRouteSource {
        override fun label(): String = "AS$asn"
        override fun dedupeKey(): String = "asn:$asn"
    }

    data class Domain(
        override val id: String,
        val hostname: String,
        override val comment: String,
        override val enabled: Boolean,
        override val orderIndex: Int,
    ) : DirectRouteSource {
        override fun label(): String = hostname
        override fun dedupeKey(): String = "domain:${hostname.lowercase()}"
    }
}
