package ru.shapovalov.bedlam.core.log

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

class AppLogTest {

    @Test
    fun `any level reaches the flow with its source and message`() {
        val log = AppLog()

        log.log(LogLevel.DEBUG, AppLog.SOURCE_APP, "quiet")
        log.log(LogLevel.WARN, AppLog.SOURCE_VPN, "loud")

        val entries = log.flow.replayCache
        assertEquals(listOf(LogLevel.DEBUG, LogLevel.WARN), entries.map { it.level })
        assertEquals(listOf("app", "vpn"), entries.map { it.source })
        assertEquals(listOf("quiet", "loud"), entries.map { it.message })
    }
}
