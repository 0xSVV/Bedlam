package ru.shapovalov.bedlam.core.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.shapovalov.bedlam.MainActivity
import ru.shapovalov.bedlam.R
import java.util.concurrent.TimeUnit
import ru.shapovalov.bedlam.di.injected
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaUri

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: HysteriaClient by injected { hysteriaClient }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null
    private var networkListener: DefaultNetworkListener? = null
    private var notificationJob: Job? = null
    private var connectionName: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onRevoke() = stop(DisconnectReason.REVOKED)

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        stopNetworkListener()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        runBlocking { runCatching { client.stop(DisconnectReason.USER) } }
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bedlam:vpn").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        wakeLock = lock
        wakeLockJob?.cancel()
        wakeLockJob = scope.launch {
            while (isActive) {
                delay(WAKE_LOCK_REFRESH_MS)
                val wl = wakeLock ?: return@launch
                if (wl.isHeld) wl.release()
                wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockJob?.cancel()
        wakeLockJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun activeUnderlyingNetwork(): Network? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                scope.launch { runCatching { client.resetConnections() } }
                return START_STICKY
            }
        }

        val uri = intent?.getStringExtra(EXTRA_URI)
        if (uri.isNullOrEmpty()) {
            Log.e(TAG, "No URI provided")
            stopSelf()
            return START_NOT_STICKY
        }

        val config = try {
            parseHysteriaUri(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URI", e)
            stopSelf()
            return START_NOT_STICKY
        }
        connectionName = config.name

        startAsForeground()
        acquireWakeLock()
        setUnderlyingNetworks(activeUnderlyingNetwork()?.let { arrayOf(it) })
        startNetworkListener()
        startNotificationLoop()

        scope.launch {
            try {
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
        val (v4Addr, v4Prefix) = parsePrefix(HysteriaClientImpl.TUN_INET4_PREFIX)
        val (v6Addr, v6Prefix) = parsePrefix(HysteriaClientImpl.TUN_INET6_PREFIX)
        val pfd = Builder()
            .setSession(connectionName.ifEmpty { getString(R.string.vpn_session_default) })
            .setMtu(mtu)
            .setMetered(false)
            .addAddress(v4Addr, v4Prefix)
            .addAddress(v6Addr, v6Prefix)
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

    private fun parsePrefix(cidr: String): Pair<String, Int> {
        val slash = cidr.lastIndexOf('/')
        require(slash > 0) { "Invalid CIDR: $cidr" }
        return cidr.substring(0, slash) to cidr.substring(slash + 1).toInt()
    }

    private fun stop(reason: DisconnectReason = DisconnectReason.USER) {
        notificationJob?.cancel()
        notificationJob = null
        stopNetworkListener()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            runCatching { client.stop(reason) }
            stopSelf()
        }
    }

    private fun startNetworkListener() {
        if (networkListener != null) return
        var seenInitial = false
        networkListener = DefaultNetworkListener(this) { network ->
            setUnderlyingNetworks(network?.let { arrayOf(it) })
            if (!seenInitial) {
                seenInitial = true
                Log.i(TAG, "Initial underlying network: $network")
                return@DefaultNetworkListener
            }
            Log.i(TAG, "Underlying network changed: $network")
            if (network != null) {
                scope.launch { client.resetConnections() }
            }
        }.also { it.start() }
    }

    private fun stopNetworkListener() {
        networkListener?.stop()
        networkListener = null
    }

    private fun startNotificationLoop() {
        notificationJob?.cancel()
        val nm = getSystemService(NotificationManager::class.java)
        notificationJob = scope.launch(Dispatchers.Default) {
            var prevTx = 0L
            var prevRx = 0L
            client.state.collectLatest { state ->
                while (coroutineContext.isActive) {
                    val s = client.stats() ?: HysteriaClient.TrafficStats(0, 0)
                    val txRate = (s.txBytes - prevTx).coerceAtLeast(0)
                    val rxRate = (s.rxBytes - prevRx).coerceAtLeast(0)
                    prevTx = s.txBytes
                    prevRx = s.rxBytes
                    nm.notify(NOTIFICATION_ID, buildNotification(state, s, txRate, rxRate))
                    delay(1000)
                }
            }
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification(
            ConnectionState.Connecting,
            HysteriaClient.TrafficStats(0, 0),
            0,
            0,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        state: ConnectionState,
        stats: HysteriaClient.TrafficStats,
        txRate: Long,
        rxRate: Long,
    ): Notification {
        val title = if (connectionName.isNotEmpty()) {
            getString(R.string.notification_title_named, connectionName)
        } else {
            getString(R.string.notification_title)
        }

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
            REQ_STOP,
            Intent(this, BedlamVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectPendingIntent = PendingIntent.getService(
            this,
            REQ_RECONNECT,
            Intent(this, BedlamVpnService::class.java).apply { action = ACTION_RECONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_vpn_tunnel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent)

        when (state) {
            is ConnectionState.Connecting -> {
                builder.setContentText(getString(R.string.notification_state_connecting))
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Connected -> {
                builder.setContentText(
                    getString(
                        R.string.notification_traffic_total,
                        formatBytes(stats.txBytes),
                        formatBytes(stats.rxBytes),
                    )
                )
                builder.setSubText(
                    getString(
                        R.string.notification_traffic_rate,
                        formatRate(txRate),
                        formatRate(rxRate),
                    )
                )
                builder.addAction(reconnectAction(reconnectPendingIntent))
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Reconnecting -> {
                builder.setContentText(
                    getString(R.string.notification_state_reconnecting, state.attempt)
                )
                builder.setSubText(state.reason)
                builder.addAction(reconnectAction(reconnectPendingIntent))
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Error -> {
                builder.setContentText(
                    getString(R.string.notification_state_error, state.message)
                )
                builder.addAction(reconnectAction(reconnectPendingIntent))
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Disconnected -> {
                builder.setContentText(getString(R.string.notification_state_disconnected))
                builder.addAction(stopAction(stopPendingIntent))
            }
        }

        return builder.build()
    }

    private fun stopAction(pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_action_stop),
            getString(R.string.action_disconnect),
            pi
        ).build()

    private fun reconnectAction(pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_action_refresh),
            getString(R.string.action_reconnect),
            pi
        ).build()

    private fun formatBytes(bytes: Long): String =
        Formatter.formatShortFileSize(this, bytes)

    private fun formatRate(bytesPerSec: Long): String =
        getString(R.string.rate_per_second, Formatter.formatShortFileSize(this, bytesPerSec))

    companion object {
        private const val TAG = "BedlamVpn"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bedlam_vpn"
        private const val REQ_STOP = 1
        private const val REQ_RECONNECT = 2
        private val WAKE_LOCK_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1)
        private val WAKE_LOCK_REFRESH_MS = TimeUnit.MINUTES.toMillis(50)
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val ACTION_RECONNECT = "ru.shapovalov.bedlam.RECONNECT_VPN"
        const val EXTRA_URI = "uri"
    }
}
