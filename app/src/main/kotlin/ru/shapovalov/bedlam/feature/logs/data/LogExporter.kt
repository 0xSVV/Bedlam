package ru.shapovalov.bedlam.feature.logs.data

import kotlinx.coroutines.flow.first
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.hysteria.api.HysteriaClient
import java.time.ZonedDateTime

@Inject
class LogExporter(
    private val buffer: LogBuffer,
    private val client: HysteriaClient,
    private val routing: RoutingRepository,
    private val appFilter: AppFilterRepository,
    private val profiles: ProfileRepository,
) {

    suspend fun export(
        device: LogExportDevice,
        exportedAt: ZonedDateTime,
        hideAddresses: Boolean,
    ): String {
        val snapshot = buffer.current()
        val header = LogExportHeader(
            exportedAt = exportedAt,
            device = device,
            connection = connectionSummary(client.state.value, exportedAt.zone),
            routing = routingSummary(routing.get(), appFilter.get()),
            dropped = snapshot.dropped,
            addressesHidden = hideAddresses,
        )
        val text = formatLogExport(snapshot.entries, header)
        if (!hideAddresses) return text
        return redactAddresses(text, redactionRules(profiles.observeAll().first(), routing.get().customDns))
    }
}
