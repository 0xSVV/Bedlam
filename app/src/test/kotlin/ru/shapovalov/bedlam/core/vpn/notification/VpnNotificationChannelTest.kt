package ru.shapovalov.bedlam.core.vpn.notification

import android.app.NotificationManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class VpnNotificationChannelTest {

    @Test
    fun `the status notification keeps its quiet channel`() {
        assertEquals("bedlam_vpn", VpnNotificationChannel.Status.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, VpnNotificationChannel.Status.importance)
    }

    @Test
    fun `alerts have a channel of their own that can be heard`() {
        assertNotEquals(VpnNotificationChannel.Status.id, VpnNotificationChannel.Alerts.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, VpnNotificationChannel.Alerts.importance)
    }

    @Test
    fun `every alert is posted on the alerts channel`() {
        VpnAlert.entries.forEach { alert ->
            assertEquals(VpnNotificationChannel.Alerts, alert.channel, alert.name)
        }
    }

    @Test
    fun `alerts neither replace the status notification nor each other`() {
        val ids = VpnAlert.entries.map { it.notificationId }
        assertEquals(ids.size, ids.toSet().size)
        assertFalse(VpnNotificationController.NOTIFICATION_ID in ids)
    }

    @Test
    fun `a tunnel stop has its own alert`() {
        assertEquals(
            setOf("ReconnectTimeout", "Revoked", "Stopped"),
            VpnAlert.entries.map { it.name }.toSet(),
        )
    }
}
