package ru.shapovalov.bedlam.core.routing.domain.model

/**
 * User-defined source of CIDRs that should bypass the tunnel.
 *
 *  - [Cidr]: a literal CIDR, resolved trivially.
 *  - [Asn]: an Autonomous System number; resolved via RIPEstat to the
 *    full set of currently-announced prefixes.
 *  - [Domain]: a hostname; resolved via DNS to its current A/AAAA records.
 *
 * Resolution happens on add and on a background schedule, never on the
 * connect path. The resolved CIDRs are persisted alongside the source.
 */
sealed interface DirectRouteSource {
    val id: String
    val comment: String
    val enabled: Boolean
    val orderIndex: Int

    /** Stable user-visible label, e.g. "10.0.0.0/8" / "AS13238" / "yandex.ru". */
    fun label(): String

    data class Cidr(
        override val id: String,
        val cidr: ru.shapovalov.bedlam.core.routing.domain.model.Cidr,
        override val comment: String,
        override val enabled: Boolean,
        override val orderIndex: Int,
    ) : DirectRouteSource {
        override fun label(): String = cidr.asString()
    }

    data class Asn(
        override val id: String,
        val asn: Int,
        override val comment: String,
        override val enabled: Boolean,
        override val orderIndex: Int,
    ) : DirectRouteSource {
        override fun label(): String = "AS$asn"
    }

    data class Domain(
        override val id: String,
        val hostname: String,
        override val comment: String,
        override val enabled: Boolean,
        override val orderIndex: Int,
    ) : DirectRouteSource {
        override fun label(): String = hostname
    }
}
