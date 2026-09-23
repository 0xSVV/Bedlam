package ru.shapovalov.bedlam.core.vpn.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import ru.shapovalov.bedlam.MainActivity
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.vpn.tile.VpnPermissionActivity

class ConnectionAlerts(private val context: Context) {

    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService(NotificationManager::class.java)) {
            "NotificationManager unavailable"
        }

    fun createChannel() {
        VpnNotificationChannel.Alerts.create(context, notificationManager)
    }

    fun postReconnectTimeout(title: String) {
        postWarning(VpnAlert.ReconnectTimeout, title, R.string.notification_reconnect_timeout)
    }

    fun cancelReconnectTimeout() {
        cancel(VpnAlert.ReconnectTimeout)
    }

    fun postRevoked(title: String) {
        postWarning(VpnAlert.Revoked, title, R.string.notification_revoked)
    }

    fun cancelRevoked() {
        cancel(VpnAlert.Revoked)
    }

    fun postStopped(reason: String) {
        val notification = Notification.Builder(context, VpnAlert.Stopped.channel.id)
            .setContentTitle(context.getString(R.string.notification_stopped_title))
            .setSmallIcon(R.drawable.ic_stat_bedlam)
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .addAction(reconnectAction())
            .build()
        post(VpnAlert.Stopped, notification)
    }

    fun cancelStopped() {
        cancel(VpnAlert.Stopped)
    }

    private fun post(alert: VpnAlert, notification: Notification) {
        createChannel()
        notificationManager.notify(alert.notificationId, notification)
    }

    private fun cancel(alert: VpnAlert) {
        notificationManager.cancel(alert.notificationId)
    }

    private fun postWarning(alert: VpnAlert, title: String, textRes: Int) {
        val text = context.getString(textRes)
        val notification = Notification.Builder(context, alert.channel.id)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_stat_bedlam)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        post(alert, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_OPEN_APP,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun reconnectAction(): Notification.Action {
        val intent = PendingIntent.getActivity(
            context,
            REQ_RECONNECT,
            Intent(context, VpnPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_refresh),
            context.getString(R.string.action_reconnect),
            intent,
        ).build()
    }

    private companion object {
        const val REQ_OPEN_APP = 0
        const val REQ_RECONNECT = 3
    }
}
