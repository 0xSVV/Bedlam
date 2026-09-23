package ru.shapovalov.bedlam.feature.logs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.feature.logs.data.LogBuffer
import ru.shapovalov.bedlam.feature.logs.data.LogExportDevice
import ru.shapovalov.bedlam.feature.logs.data.LogExporter
import ru.shapovalov.bedlam.testing.FakeAppFilterRepository
import ru.shapovalov.bedlam.testing.FakeHysteriaClient
import ru.shapovalov.bedlam.testing.FakeRoutingRepository
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class LogExporterTest {

    private val client = FakeHysteriaClient(ConnectionState.Connecting)
    private val routing = FakeRoutingRepository(RoutingConfig(mtu = 1400))
    private val appFilter = FakeAppFilterRepository(AppFilter(AppFilterMode.Blocklist, setOf("a", "b")))

    private val device = LogExportDevice(
        appVersionName = "1.6.5",
        appVersionCode = 10605L,
        coreVersion = "v2.12.3",
        manufacturer = "Acme",
        model = "Phone 9",
        androidRelease = "16",
        sdkInt = 36,
        abis = listOf("arm64-v8a"),
    )
    private val exportedAt = ZonedDateTime.of(2026, 9, 24, 15, 41, 40, 0, ZoneOffset.UTC)

    private fun TestScope.exporter(): LogExporter =
        LogExporter(LogBuffer(client, backgroundScope), client, routing, appFilter)

    @Test
    fun `the export holds every level the buffer keeps`() = runTest {
        val exporter = exporter()
        runCurrent()
        client.logEntries.emit(logEntry(1, LogLevel.DEBUG))
        client.logEntries.emit(logEntry(2, LogLevel.INFO))
        client.logEntries.emit(logEntry(3, LogLevel.ERROR))
        runCurrent()

        val text = exporter.export(device, exportedAt)

        assertTrue(text.contains("DEBUG test line 1"), text)
        assertTrue(text.contains("INFO test line 2"), text)
        assertTrue(text.contains("ERROR test line 3"), text)
        assertTrue(text.contains("Lines: 3 (DEBUG 1, INFO 1, WARN 0, ERROR 1)"), text)
    }

    @Test
    fun `the header reports the live connection and routing`() = runTest {
        val exporter = exporter()
        runCurrent()

        val lines = exporter.export(device, exportedAt).lines()

        assertEquals("Exported 2026-09-24 15:41:40 +00:00", lines[1])
        assertEquals("Connection: Connecting", lines[3])
        assertEquals(
            "Routing: DNS Cloudflare over TCP · IPv6 on · MTU 1400 · blocklist of 2 apps · LAN bypass on · 0 direct-route sources",
            lines[4],
        )
    }
}
