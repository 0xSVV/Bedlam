package ru.shapovalov.bedlam.core.log

import android.app.ApplicationExitInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.time.LocalDateTime
import java.time.ZoneOffset

class ProcessExitTest {

    private val cached = 400
    private val gone = 1000
    private val service = 300
    private val foregroundService = 125
    private val foreground = 100

    @Test
    fun `reason codes match the platform constants`() {
        val platform = mapOf(
            ApplicationExitInfo.REASON_UNKNOWN to "UNKNOWN",
            ApplicationExitInfo.REASON_EXIT_SELF to "EXIT_SELF",
            ApplicationExitInfo.REASON_SIGNALED to "SIGNALED",
            ApplicationExitInfo.REASON_LOW_MEMORY to "LOW_MEMORY",
            ApplicationExitInfo.REASON_CRASH to "CRASH",
            ApplicationExitInfo.REASON_CRASH_NATIVE to "CRASH_NATIVE",
            ApplicationExitInfo.REASON_ANR to "ANR",
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to "INITIALIZATION_FAILURE",
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to "PERMISSION_CHANGE",
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to "EXCESSIVE_RESOURCE_USAGE",
            ApplicationExitInfo.REASON_USER_REQUESTED to "USER_REQUESTED",
            ApplicationExitInfo.REASON_USER_STOPPED to "USER_STOPPED",
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to "DEPENDENCY_DIED",
            ApplicationExitInfo.REASON_OTHER to "OTHER",
            ApplicationExitInfo.REASON_FREEZER to "FREEZER",
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE to "PACKAGE_STATE_CHANGE",
            ApplicationExitInfo.REASON_PACKAGE_UPDATED to "PACKAGE_UPDATED",
        )

        platform.forEach { (code, label) -> assertEquals(label, processExitReasonLabel(code)) }
    }

    @Test
    fun `newer reasons have labels instead of bare numbers`() {
        assertEquals("USER_STOPPED", processExitReasonLabel(11))
        assertEquals("FREEZER", processExitReasonLabel(14))
        assertEquals("PACKAGE_STATE_CHANGE", processExitReasonLabel(15))
        assertEquals("PACKAGE_UPDATED", processExitReasonLabel(16))
        assertEquals("REASON_99", processExitReasonLabel(99))
    }

    @Test
    fun `crashes and ANRs are errors at any importance`() {
        listOf(ProcessExitReason.CRASH, ProcessExitReason.CRASH_NATIVE, ProcessExitReason.ANR)
            .forEach { reason ->
                listOf(foreground, foregroundService, service, cached, gone).forEach { importance ->
                    assertEquals(LogLevel.ERROR, processExitLevel(reason.code, importance), "$reason $importance")
                }
            }
    }

    @Test
    fun `kills by the system are warnings`() {
        listOf(
            ProcessExitReason.LOW_MEMORY,
            ProcessExitReason.SIGNALED,
            ProcessExitReason.OTHER,
            ProcessExitReason.FREEZER,
            ProcessExitReason.EXCESSIVE_RESOURCE_USAGE,
            ProcessExitReason.DEPENDENCY_DIED,
            ProcessExitReason.INITIALIZATION_FAILURE,
            ProcessExitReason.PERMISSION_CHANGE,
            ProcessExitReason.UNKNOWN,
        ).forEach { reason ->
            assertEquals(LogLevel.WARN, processExitLevel(reason.code, cached), "$reason")
        }
        assertEquals(LogLevel.WARN, processExitLevel(99, cached))
    }

    @Test
    fun `a swipe away of a cached process is debug`() {
        listOf(
            ProcessExitReason.USER_REQUESTED,
            ProcessExitReason.EXIT_SELF,
            ProcessExitReason.USER_STOPPED,
            ProcessExitReason.PACKAGE_STATE_CHANGE,
            ProcessExitReason.PACKAGE_UPDATED,
        ).forEach { reason ->
            assertEquals(LogLevel.DEBUG, processExitLevel(reason.code, cached), "$reason")
            assertEquals(LogLevel.DEBUG, processExitLevel(reason.code, gone), "$reason")
            assertEquals(LogLevel.INFO, processExitLevel(reason.code, service), "$reason")
        }
    }

    @ParameterizedTest
    @EnumSource(ProcessExitReason::class)
    fun `an exit while the tunnel ran in the foreground is at least a warning`(reason: ProcessExitReason) {
        listOf(foreground, foregroundService).forEach { importance ->
            val level = processExitLevel(reason.code, importance)
            assert(level >= LogLevel.WARN) { "$reason $importance gave $level" }
        }
    }

    @Test
    fun `the message names the reason, importance, description and time`() {
        val zone = ZoneOffset.ofHours(3)
        val at = LocalDateTime.of(2026, 9, 24, 15, 41, 40).toInstant(zone).toEpochMilli()

        val message = processExitMessage(
            code = ApplicationExitInfo.REASON_USER_REQUESTED,
            importance = cached,
            description = "[REMOVE TASK] remove task",
            timestampMillis = at,
            zone = zone,
        )

        assertEquals(
            "Last process exit at 2026-09-24 15:41:40: USER_REQUESTED, importance 400, [REMOVE TASK] remove task",
            message,
        )
        assertEquals(
            "Last process exit at 2026-09-24 15:41:40: FREEZER, importance 125",
            processExitMessage(14, foregroundService, null, at, zone),
        )
    }
}
