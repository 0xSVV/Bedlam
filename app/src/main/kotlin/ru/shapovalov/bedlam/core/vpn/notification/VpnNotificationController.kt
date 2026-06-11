package ru.shapovalov.bedlam.core.vpn.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import ru.shapovalov.bedlam.MainActivity
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.util.formatBytes
import ru.shapovalov.bedlam.core.util.formatRate
import ru.shapovalov.bedlam.core.vpn.BedlamVpnService
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient

class VpnNotificationController(private val context: Context) {

    @Volatile
    var connectionName: String = ""

    private val notificationManager: NotificationManager =
        requireNotNull(context.getSystemService(NotificationManager::class.java)) {
            "NotificationManager unavailable"
        }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun foregroundNotification(): Notification =
        build(ConnectionState.Connecting, HysteriaClient.TrafficStats(0, 0), 0, 0)

    fun post(
        state: ConnectionState,
        stats: HysteriaClient.TrafficStats,
        txRate: Long,
        rxRate: Long,
    ) {
        notificationManager.notify(NOTIFICATION_ID, build(state, stats, txRate, rxRate))
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun postReconnectTimeoutWarning() {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title())
            .setSmallIcon(R.drawable.ic_stat_bedlam)
            .setContentText(context.getString(R.string.notification_reconnect_timeout))
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(context.getString(R.string.notification_reconnect_timeout))
            )
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        notificationManager.notify(WARNING_NOTIFICATION_ID, notification)
    }

    private fun build(
        state: ConnectionState,
        stats: HysteriaClient.TrafficStats,
        txRate: Long,
        rxRate: Long,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title())
            .setSmallIcon(R.drawable.ic_stat_bedlam)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())

        applyState(builder, state, stats, txRate, rxRate)
        return builder.build()
    }

    private fun applyState(
        builder: Notification.Builder,
        state: ConnectionState,
        stats: HysteriaClient.TrafficStats,
        txRate: Long,
        rxRate: Long,
    ) {
        when (state) {
            is ConnectionState.Connecting -> {
                builder.setContentText(context.getString(R.string.notification_state_connecting))
                builder.addAction(stopAction())
            }

            is ConnectionState.Connected -> {
                val rateLine = context.getString(
                    R.string.notification_traffic_rate,
                    context.formatRate(txRate),
                    context.formatRate(rxRate),
                )
                val sessionLine = context.getString(
                    R.string.notification_detail_session,
                    context.formatBytes(stats.txBytes),
                    context.formatBytes(stats.rxBytes),
                )
                val serverLine = context.getString(
                    if (state.info.udpEnabled) {
                        R.string.notification_detail_server_udp
                    } else {
                        R.string.notification_detail_server
                    },
                    state.info.serverAddress,
                )
                builder.setContentText(rateLine)
                builder.setStyle(
                    Notification.BigTextStyle()
                        .bigText(listOf(rateLine, sessionLine, serverLine).joinToString("\n"))
                )
                builder.setWhen(state.connectedSinceMillis)
                builder.setShowWhen(true)
                builder.setUsesChronometer(true)
                builder.addAction(reconnectAction())
                builder.addAction(stopAction())
            }

            is ConnectionState.Reconnecting -> {
                builder.setContentText(
                    context.getString(R.string.notification_state_reconnecting, state.attempt)
                )
                builder.setSubText(state.reason)
                builder.addAction(reconnectAction())
                builder.addAction(stopAction())
            }

            is ConnectionState.Error -> {
                builder.setContentText(
                    context.getString(R.string.notification_state_error, state.message)
                )
                builder.addAction(reconnectAction())
                builder.addAction(stopAction())
            }

            is ConnectionState.Disconnected -> {
                builder.setContentText(context.getString(R.string.notification_state_disconnected))
                builder.addAction(stopAction())
            }
        }
    }

    private fun title(): String =
        if (connectionName.isNotEmpty()) {
            context.getString(R.string.notification_title_named, connectionName)
        } else {
            context.getString(R.string.notification_title)
        }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopAction(): Notification.Action = actionFor(
        requestCode = REQ_STOP,
        action = BedlamVpnService.ACTION_STOP,
        iconRes = R.drawable.ic_action_stop,
        labelRes = R.string.action_disconnect,
    )

    private fun reconnectAction(): Notification.Action = actionFor(
        requestCode = REQ_RECONNECT,
        action = BedlamVpnService.ACTION_RECONNECT,
        iconRes = R.drawable.ic_action_refresh,
        labelRes = R.string.action_reconnect,
    )

    private fun actionFor(
        requestCode: Int,
        action: String,
        iconRes: Int,
        labelRes: Int,
    ): Notification.Action {
        val pi = PendingIntent.getService(
            context,
            requestCode,
            Intent(context, BedlamVpnService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, iconRes),
            context.getString(labelRes),
            pi,
        ).build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private const val WARNING_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "bedlam_vpn"
        private const val REQ_STOP = 1
        private const val REQ_RECONNECT = 2
    }
}
