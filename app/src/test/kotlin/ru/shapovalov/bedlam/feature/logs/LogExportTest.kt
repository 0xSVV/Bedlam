package ru.shapovalov.bedlam.feature.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.logs.data.DroppedLines
import ru.shapovalov.bedlam.feature.logs.data.LogExportDevice
import ru.shapovalov.bedlam.feature.logs.data.LogExportHeader
import ru.shapovalov.bedlam.feature.logs.data.formatLogExport
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LogExportTest {

    private val zone = ZoneOffset.ofHours(3)

    private val header = LogExportHeader(
        exportedAt = ZonedDateTime.of(2026, 9, 24, 15, 41, 40, 0, zone),
        device = LogExportDevice(
            appVersionName = "1.6.5",
            appVersionCode = 10605L,
            coreVersion = "v2.12.3",
            manufacturer = "Acme",
            model = "Phone 9",
            androidRelease = "16",
            sdkInt = 36,
            abis = listOf("arm64-v8a", "armeabi-v7a"),
        ),
        connection = "Connected",
        routing = "DNS Cloudflare over TLS",
        dropped = DroppedLines(),
        addressesHidden = false,
    )

    private fun at(day: Int, hour: Int, minute: Int, second: Int, millis: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute, second, millis * 1_000_000)
            .toInstant(zone)
            .toEpochMilli()

    private fun entry(
        message: String,
        source: String = "tunnel",
        level: LogLevel = LogLevel.WARN,
        timestampMillis: Long = at(24, 15, 0, 0),
    ) = LogEntry(
        level = level,
        source = source,
        message = message,
        timestampMillis = timestampMillis,
        seq = 1L,
    )

    @Test
    fun `header carries the metadata a report always needs`() {
        val text = formatLogExport(listOf(entry("hello")), header)
        val lines = text.lines()

        assertEquals("Bedlam 1.6.5 (10605) · Hysteria core v2.12.3", lines[0])
        assertEquals("Exported 2026-09-24 15:41:40 +03:00", lines[1])
        assertEquals("Acme Phone 9 · Android 16 (SDK 36) · arm64-v8a, armeabi-v7a", lines[2])
        assertEquals("Connection: Connected", lines[3])
        assertEquals("Routing: DNS Cloudflare over TLS", lines[4])
        assertEquals("Lines: 1 (DEBUG 0, INFO 0, WARN 1, ERROR 0)", lines[5])
        assertEquals("Dropped: DEBUG 0, INFO and above 0", lines[6])
        assertEquals("", lines[7])
    }

    @Test
    fun `lines are counted per level and dropped lines per tier`() {
        val entries = listOf(
            entry("a", level = LogLevel.DEBUG),
            entry("b", level = LogLevel.DEBUG),
            entry("c", level = LogLevel.INFO),
            entry("d", level = LogLevel.ERROR),
        )
        val dropped = DroppedLines(debug = 5000L, info = 3L, warn = 2L, error = 1L)

        val lines = formatLogExport(entries, header.copy(dropped = dropped)).lines()

        assertEquals("Lines: 4 (DEBUG 2, INFO 1, WARN 0, ERROR 1)", lines[5])
        assertEquals("Dropped: DEBUG 5000, INFO and above 6", lines[6])
    }

    @Test
    fun `a hidden addresses export says so in the header`() {
        assertFalse(formatLogExport(emptyList(), header).contains("Addresses hidden"))

        val lines = formatLogExport(emptyList(), header.copy(addressesHidden = true)).lines()
        assertEquals("Addresses hidden", lines[7])
        assertEquals("", lines[8])
    }

    @Test
    fun `a date line opens the body and marks every change of day`() {
        val entries = listOf(
            entry("late", timestampMillis = at(22, 23, 59, 58, 506)),
            entry("later", timestampMillis = at(22, 23, 59, 59, 1)),
            entry("next day", timestampMillis = at(23, 0, 0, 1)),
            entry("two days on", timestampMillis = at(24, 9, 30, 0)),
        )

        val body = formatLogExport(entries, header).lines().drop(8)

        assertEquals(
            listOf(
                "-- 2026-09-22 --",
                "23:59:58.506 WARN tunnel late",
                "23:59:59.001 WARN tunnel later",
                "-- 2026-09-23 --",
                "00:00:01.000 WARN tunnel next day",
                "-- 2026-09-24 --",
                "09:30:00.000 WARN tunnel two days on",
                "",
            ),
            body,
        )
    }

    @Test
    fun `timestamps follow the export time zone`() {
        val utc = header.copy(exportedAt = header.exportedAt.withZoneSameInstant(ZoneOffset.UTC))

        val body = formatLogExport(listOf(entry("x", timestampMillis = at(24, 1, 0, 0))), utc)
            .lines().drop(8)

        assertEquals("-- 2026-09-23 --", body[0])
        assertEquals("22:00:00.000 WARN tunnel x", body[1])
    }

    @Test
    fun `long messages are exported untruncated`() {
        val long = "x".repeat(300)
        val text = formatLogExport(listOf(entry(long)), header)
        assertTrue(text.contains(long))
    }

    @Test
    fun `a blank source is omitted rather than padded`() {
        val text = formatLogExport(listOf(entry("msg", source = "")), header)
        assertTrue(text.contains("WARN msg"))
    }
}
