package ru.shapovalov.bedlam.core.routing.domain.usecase

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.data.RoutePresets
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import java.util.UUID

@Inject
class AddPresetUseCase(
    private val addSource: AddRouteSourceUseCase,
) {
    suspend operator fun invoke(presetId: String) {
        val preset = RoutePresets.byId(presetId) ?: return
        preset.asns.forEachIndexed { index, entry ->
            addSource(
                DirectRouteSource.Asn(
                    id = UUID.randomUUID().toString(),
                    asn = entry.asn,
                    comment = entry.comment,
                    enabled = true,
                    orderIndex = index,
                )
            )
        }
    }
}
