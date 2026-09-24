package ru.shapovalov.bedlam.core.vpn

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RevokeWordingTest {

    private val mainSourceSet = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun revokedNotificationText(): String {
        val strings = File(mainSourceSet, "res/values/strings.xml").readText()
        val match = Regex("""<string name="notification_revoked">(.*?)</string>""").find(strings)
        return requireNotNull(match).groupValues[1]
    }

    private fun assertNamesBothCauses(text: String) {
        assertTrue(text.contains("another VPN app", ignoreCase = true), text)
        assertTrue(text.contains("system settings", ignoreCase = true), text)
    }

    @Test
    fun `the revoke log line names both causes`() {
        assertNamesBothCauses(BedlamVpnService.REVOKED_LOG_LINE)
    }

    @Test
    fun `the revoke notification names both causes`() {
        assertNamesBothCauses(revokedNotificationText())
    }
}
