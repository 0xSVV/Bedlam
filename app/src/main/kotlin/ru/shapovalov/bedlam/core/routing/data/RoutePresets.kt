package ru.shapovalov.bedlam.core.routing.data

import ru.shapovalov.bedlam.core.routing.domain.model.RoutePreset

/**
 * Hardcoded curated presets. Updates ship in app releases; the resolved CIDRs
 * stay current via the daily refresh worker since the underlying ASNs are stable.
 */
object RoutePresets {

    val ALL: List<RoutePreset> = listOf(
        RoutePreset(
            id = "ru-web",
            name = "Russian web giants",
            description = "Yandex, VK, Mail.ru, Ozon, Wildberries — direct path so the tunnel doesn't get geo-blocked.",
            asns = listOf(
                RoutePreset.AsnEntry(13238, "Yandex"),
                RoutePreset.AsnEntry(208722, "Yandex Cloud"),
                RoutePreset.AsnEntry(47541, "VK"),
                RoutePreset.AsnEntry(47542, "VK Cloud"),
                RoutePreset.AsnEntry(21051, "Mail.ru"),
                RoutePreset.AsnEntry(43247, "Ozon"),
                RoutePreset.AsnEntry(200976, "Wildberries"),
            ),
        ),
        RoutePreset(
            id = "ru-bank",
            name = "Russian banking & gov",
            description = "Sberbank, Tinkoff, VTB, Alfa-Bank, Gosuslugi — these usually geo-block foreign IPs.",
            asns = listOf(
                RoutePreset.AsnEntry(35237, "Sberbank"),
                RoutePreset.AsnEntry(205638, "Tinkoff (T-Bank)"),
                RoutePreset.AsnEntry(47172, "VTB"),
                RoutePreset.AsnEntry(15632, "Alfa-Bank"),
                RoutePreset.AsnEntry(50544, "Gosuslugi (Rostelecom DC)"),
            ),
        ),
        RoutePreset(
            id = "ai",
            name = "AI services",
            description = "OpenAI, Anthropic — useful if your VPN exit is in a region they block.",
            asns = listOf(
                RoutePreset.AsnEntry(20473, "Choopa/Vultr (OpenAI infra)"),
                RoutePreset.AsnEntry(8075, "Microsoft (Azure OpenAI)"),
            ),
        ),
        RoutePreset(
            id = "cdn",
            name = "Major CDNs",
            description = "Cloudflare, Fastly, Akamai — bypass tunnel for CDN-served content.",
            asns = listOf(
                RoutePreset.AsnEntry(13335, "Cloudflare"),
                RoutePreset.AsnEntry(54113, "Fastly"),
                RoutePreset.AsnEntry(20940, "Akamai"),
            ),
        ),
    )

    fun byId(id: String): RoutePreset? = ALL.firstOrNull { it.id == id }
}
