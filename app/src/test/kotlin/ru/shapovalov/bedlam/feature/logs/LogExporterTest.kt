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
import ru.shapovalov.bedlam.testing.FakeProfileRepository
import ru.shapovalov.bedlam.testing.FakeRoutingRepository
import ru.shapovalov.bedlam.testing.logEntry
import ru.shapovalov.bedlam.testing.testProfile
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.ZoneOffset
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class LogExporterTest {

    private val client = FakeHysteriaClient(ConnectionState.Connecting)
    private val routing = FakeRoutingRepository(RoutingConfig(mtu = 1400))
    private val appFilter = FakeAppFilterRepository(AppFilter(AppFilterMode.Blocklist, setOf("a", "b")))
    private val profiles = FakeProfileRepository(listOf(testProfile("home", address = "vpn.example.com:443")))

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
        LogExporter(LogBuffer(client, backgroundScope), client, routing, appFilter, profiles)

    @Test
    fun `the export holds every level the buffer keeps`() = runTest {
        val exporter = exporter()
        runCurrent()
        client.logEntries.emit(logEntry(1, LogLevel.DEBUG))
        client.logEntries.emit(logEntry(2, LogLevel.INFO))
        client.logEntries.emit(logEntry(3, LogLevel.ERROR))
        runCurrent()

        val text = exporter.export(device, exportedAt, hideAddresses = false)

        assertTrue(text.contains("DEBUG test line 1"), text)
        assertTrue(text.contains("INFO test line 2"), text)
        assertTrue(text.contains("ERROR test line 3"), text)
        assertTrue(text.contains("Lines: 3 (DEBUG 1, INFO 1, WARN 0, ERROR 1)"), text)
    }

    @Test
    fun `the header reports the live connection and routing`() = runTest {
        val exporter = exporter()
        runCurrent()

        val lines = exporter.export(device, exportedAt, hideAddresses = false).lines()

        assertEquals("Exported 2026-09-24 15:41:40 +00:00", lines[1])
        assertEquals("Connection: Connecting", lines[3])
        assertEquals(
            "Routing: DNS Cloudflare over TCP · IPv6 on · MTU 1400 · blocklist of 2 apps · LAN bypass on · 0 direct-route sources",
            lines[4],
        )
    }

    @Test
    fun `hiding addresses masks public addresses and saved server hosts`() = runTest {
        val exporter = exporter()
        runCurrent()
        client.connectionState.value = ConnectionState.Reconnecting(2, "dial 203.0.113.7:443 failed")
        client.logEntries.emit(
            logEntry(1).copy(message = "Connecting to vpn.example.com:443 via tls|1.1.1.1:853 from 192.168.1.5")
        )
        runCurrent()

        val plain = exporter.export(device, exportedAt, hideAddresses = false)
        val hidden = exporter.export(device, exportedAt, hideAddresses = true)

        assertTrue(plain.contains("Connecting to vpn.example.com:443 via tls|1.1.1.1:853 from 192.168.1.5"), plain)
        assertTrue(plain.contains("dial 203.0.113.7:443 failed"), plain)
        assertTrue(hidden.contains("Connecting to <host-1>:443 via tls|1.1.1.1:853 from 192.168.1.5"), hidden)
        assertTrue(hidden.contains("Connection: Reconnecting, attempt 2: dial <ip-1>:443 failed"), hidden)
        assertTrue(hidden.contains("\nAddresses hidden\n"), hidden)
        assertTrue(hidden.contains("Bedlam 1.6.5 (10605) · Hysteria core v2.12.3"), hidden)
    }
}
