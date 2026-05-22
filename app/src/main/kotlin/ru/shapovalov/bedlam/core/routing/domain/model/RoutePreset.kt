package ru.shapovalov.bedlam.core.routing.domain.model

/** A curated bundle of ASNs the user can add in one tap. */
data class RoutePreset(
    val id: String,
    val name: String,
    val description: String,
    val asns: List<AsnEntry>,
) {
    data class AsnEntry(val asn: Int, val comment: String)
}
