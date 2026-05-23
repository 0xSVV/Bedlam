package ru.shapovalov.bedlam.core.routing.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.data.RoutePresets
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import java.util.UUID

@Inject
class AddPresetUseCase(
    private val addSource: AddRouteSourceUseCase,
) {
    suspend operator fun invoke(presetId: String): Int {
        val preset = RoutePresets.byId(presetId) ?: return 0
        var added = 0
        preset.asns.forEachIndexed { index, entry ->
            val created = addSource(
                DirectRouteSource.Asn(
                    id = UUID.randomUUID().toString(),
                    asn = entry.asn,
                    comment = entry.comment,
                    enabled = true,
                    orderIndex = index,
                )
            )
            if (created) added++
        }
        return added
    }
}
