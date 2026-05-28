package ru.shapovalov.bedlam.core.routing.data

import ru.shapovalov.bedlam.core.routing.domain.model.RoutePreset

object RoutePresets {

    val ALL: List<RoutePreset> = listOf(
        RoutePreset(
            id = "ru-big-tech",
            name = "Big tech",
            description = "Yandex, VK, Mail.ru, Ozon, Wildberries.",
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
            name = "Banking & gov",
            description = "Sberbank, Tinkoff, VTB, Alfa-Bank, Gosuslugi.",
            asns = listOf(
                RoutePreset.AsnEntry(35237, "Sberbank"),
                RoutePreset.AsnEntry(205638, "Tinkoff (T-Bank)"),
                RoutePreset.AsnEntry(47172, "VTB"),
                RoutePreset.AsnEntry(15632, "Alfa-Bank"),
                RoutePreset.AsnEntry(50544, "Gosuslugi (Rostelecom DC)"),
            ),
        ),
        RoutePreset(
            id = "ru-telco",
            name = "Telcos & ISPs",
            description = "MTS, Megafon, Beeline, Rostelecom.",
            asns = listOf(
                RoutePreset.AsnEntry(8359, "MTS"),
                RoutePreset.AsnEntry(31133, "Megafon"),
                RoutePreset.AsnEntry(3216, "VimpelCom / Beeline"),
                RoutePreset.AsnEntry(12389, "Rostelecom"),
            ),
        ),
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

    fun byId(id: String): RoutePreset? = ALL.firstOrNull { it.id == id }
}
