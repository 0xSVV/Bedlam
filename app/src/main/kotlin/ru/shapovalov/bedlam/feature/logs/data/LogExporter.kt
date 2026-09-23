package ru.shapovalov.bedlam.feature.logs.data

import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.hysteria.api.HysteriaClient
import java.time.ZonedDateTime

@Inject
class LogExporter(
    private val buffer: LogBuffer,
    private val client: HysteriaClient,
    private val routing: RoutingRepository,
    private val appFilter: AppFilterRepository,
) {

    suspend fun export(device: LogExportDevice, exportedAt: ZonedDateTime): String {
        val snapshot = buffer.current()
        val header = LogExportHeader(
            exportedAt = exportedAt,
            device = device,
            connection = connectionSummary(client.state.value, exportedAt.zone),
            routing = routingSummary(routing.get(), appFilter.get()),
            dropped = snapshot.dropped,
            addressesHidden = false,
        )
        return formatLogExport(snapshot.entries, header)
    }
}
