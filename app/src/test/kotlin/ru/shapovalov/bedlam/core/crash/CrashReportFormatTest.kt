package ru.shapovalov.bedlam.core.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrashReportFormatTest {

    @Test
    fun `a report names the build, the thread, the exception and its cause`() {
        val cause = IllegalStateException("tunnel gone")
        val failure = RuntimeException("startup failed", cause)

        val report = formatCrashReport(
            header = "Bedlam 1.6.3\nsamsung SM-A5260 · Android SDK 34",
            thread = Thread.currentThread(),
            throwable = failure,
            timestampMillis = 0L,
        )

        val lines = report.lines()
        assertEquals("Bedlam 1.6.3", lines[0])
        assertEquals("samsung SM-A5260 · Android SDK 34", lines[1])
        assertTrue(lines[2].startsWith("Crashed at 1970-01-01 "), lines[2])
        assertTrue(lines[2].endsWith("on thread ${Thread.currentThread().name}"), lines[2])
        assertEquals("", lines[3])
        assertEquals("java.lang.RuntimeException: startup failed", lines[4])
        assertTrue(lines[5].trimStart().startsWith("at "), lines[5])
        assertTrue(report.contains("Caused by: java.lang.IllegalStateException: tunnel gone"), report)
        assertTrue('\r' !in report)
    }
}
