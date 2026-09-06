package ru.shapovalov.bedlam.feature.logs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.feature.logs.data.LogExportHeader
import ru.shapovalov.bedlam.feature.logs.data.formatLogExport
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

class LogExportTest {

    private val header = LogExportHeader(
        appVersion = "1.5.3",
        device = "Acme Phone",
        sdkInt = 37,
        minLevel = LogLevel.INFO,
        droppedCount = 0L,
    )

    private fun entry(message: String, source: String = "tunnel") = LogEntry(
        level = LogLevel.WARN,
        source = source,
        message = message,
        timestampMillis = 0L,
        seq = 1L,
    )

    @Test
    fun `header carries the metadata a report always needs`() {
        val text = formatLogExport(listOf(entry("hello")), header)
        val lines = text.lines()

        assertEquals("Bedlam 1.5.3", lines[0])
        assertEquals("Acme Phone · Android SDK 37", lines[1])
        assertEquals("Filter INFO · 1 lines", lines[2])
    }

    @Test
    fun `dropped lines are reported only once the cap was hit`() {
        assertFalse(formatLogExport(emptyList(), header).contains("dropped"))
        val text = formatLogExport(emptyList(), header.copy(droppedCount = 12L))
        assertTrue(text.contains("12 earlier lines dropped"))
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
