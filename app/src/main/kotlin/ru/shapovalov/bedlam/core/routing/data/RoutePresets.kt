package ru.shapovalov.bedlam.core.routing.data

import ru.shapovalov.bedlam.core.routing.domain.model.RoutePreset

object RoutePresets {

    val ALL: List<RoutePreset> = listOf(
        RoutePreset(
            id = "cf",
            name = "Cloudflare",
            description = "Cloudflare's anycast network (~600 prefixes)",
            asns = listOf(
                RoutePreset.AsnEntry(13335, "Cloudflare"),
            ),
        ),
        RoutePreset(
            id = "google",
            name = "Google",
            description = "Google services and Google Cloud.",
            asns = listOf(
                RoutePreset.AsnEntry(15169, "Google"),
                RoutePreset.AsnEntry(396982, "Google Cloud"),
                RoutePreset.AsnEntry(36492, "Google"),
            ),
        ),
    )

    fun byId(id: String): RoutePreset? = ALL.firstOrNull { it.id == id }
}
