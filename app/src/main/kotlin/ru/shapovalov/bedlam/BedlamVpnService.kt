package ru.shapovalov.bedlam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaUri

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: HysteriaClient = HysteriaClientImpl

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        val uri = intent?.getStringExtra(EXTRA_URI)
        if (uri.isNullOrEmpty()) {
            Log.e(TAG, "No URI provided")
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        scope.launch {
            try {
                val config = parseHysteriaUri(uri)
                client.start(
                    config = config,
                    protector = { fd -> protect(fd) },
                    tun = { mtu -> establishTun(mtu) },
                )
            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                stop()
            }
        }

        return START_STICKY
    }

    private fun establishTun(mtu: Int): Int {
        val pfd = Builder()
            .setSession("Bedlam")
            .setMtu(mtu)
            .addAddress("172.19.0.1", 30)
            .addAddress("fdfe:dcba:9876::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
            .addDnsServer("2606:4700:4700::1111")
            .addDnsServer("2606:4700:4700::1001")
            .establish()
            ?: throw IllegalStateException("VpnService.establish() returned null")
        return pfd.detachFd()
    }

    private fun stop() {
        client.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() = stop()

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bedlam VPN tunnel status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BedlamVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bedlam")
            .setContentText("VPN tunnel active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(null, "Disconnect", stopPendingIntent).build()
            )
            .build()
    }

    companion object {
        private const val TAG = "BedlamVpn"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bedlam_vpn"
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val EXTRA_URI = "uri"
    }
}
