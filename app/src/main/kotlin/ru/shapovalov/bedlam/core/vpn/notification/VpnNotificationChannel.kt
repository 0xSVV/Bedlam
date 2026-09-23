package ru.shapovalov.bedlam.core.vpn.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import ru.shapovalov.bedlam.R

enum class VpnNotificationChannel(
    val id: String,
    val importance: Int,
    val nameRes: Int,
    val descriptionRes: Int,
    val showBadge: Boolean,
) {
    Status(
        id = "bedlam_vpn",
        importance = NotificationManager.IMPORTANCE_LOW,
        nameRes = R.string.notification_channel_name,
        descriptionRes = R.string.notification_channel_description,
        showBadge = false,
    ),
    Alerts(
        id = "bedlam_vpn_alerts",
        importance = NotificationManager.IMPORTANCE_DEFAULT,
        nameRes = R.string.notification_alerts_channel_name,
        descriptionRes = R.string.notification_alerts_channel_description,
        showBadge = true,
    );

    fun create(context: Context, manager: NotificationManager) {
        val channel = NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(descriptionRes)
            setShowBadge(showBadge)
        }
        manager.createNotificationChannel(channel)
    }
}

enum class VpnAlert(val notificationId: Int) {
    ReconnectTimeout(2),
    Revoked(4),
    Stopped(5);

    val channel: VpnNotificationChannel
        get() = VpnNotificationChannel.Alerts
}
