package ru.shapovalov.bedlam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
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
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.HysteriaClientImpl
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.parseHysteriaUri
import kotlin.coroutines.coroutineContext

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: HysteriaClient = HysteriaClientImpl
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkListener: DefaultNetworkListener? = null
    private var notificationJob: Job? = null
    private var connectionName: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onRevoke() = stop()

    override fun onDestroy() {
        releaseWakeLock()
        stop()
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bedlam:vpn").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun activeUnderlyingNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            .setSession(if (connectionName.isNotEmpty()) connectionName else "Bedlam")
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

    private fun stop() {
        notificationJob?.cancel()
        notificationJob = null
        stopNetworkListener()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Thread({
            runCatching { client.stop() }
            stopSelf()
        }, "BedlamVpnStop").start()
    }

    private fun startNetworkListener() {
        if (networkListener != null) return
        networkListener = DefaultNetworkListener(this) { network ->
            Log.i(TAG, "Underlying network changed: $network")
            setUnderlyingNetworks(network?.let { arrayOf(it) })
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
                    val s = client.stats()
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
        startForeground(
            NOTIFICATION_ID,
            buildNotification(ConnectionState.Connecting, HysteriaClient.TrafficStats(0, 0), 0, 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
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

    private fun buildNotification(
        state: ConnectionState,
        stats: HysteriaClient.TrafficStats,
        txRate: Long,
        rxRate: Long,
    ): Notification {
        val title = if (connectionName.isNotEmpty()) "Bedlam · $connectionName" else "Bedlam"

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
                builder.setContentText("Connecting…")
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Connected -> {
                builder.setContentText(
                    "↑ ${formatBytes(stats.txBytes)} · ↓ ${formatBytes(stats.rxBytes)}"
                )
                builder.setSubText(
                    "↑ ${formatRate(txRate)} · ↓ ${formatRate(rxRate)}"
                )
                builder.addAction(reconnectAction(reconnectPendingIntent))
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Error -> {
                builder.setContentText("Error: ${state.message}")
                builder.addAction(reconnectAction(reconnectPendingIntent))
                builder.addAction(stopAction(stopPendingIntent))
            }
            is ConnectionState.Disconnected -> {
                builder.setContentText("Disconnected")
                builder.addAction(stopAction(stopPendingIntent))
            }
        }

        return builder.build()
    }

    private fun stopAction(pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_action_stop),
            "Disconnect",
            pi
        ).build()

    private fun reconnectAction(pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_action_refresh),
            "Reconnect",
            pi
        ).build()

    private fun formatBytes(bytes: Long): String =
        Formatter.formatShortFileSize(this, bytes)

    private fun formatRate(bytesPerSec: Long): String =
        "${Formatter.formatShortFileSize(this, bytesPerSec)}/s"

    companion object {
        private const val TAG = "BedlamVpn"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bedlam_vpn"
        private const val REQ_STOP = 1
        private const val REQ_RECONNECT = 2
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val ACTION_RECONNECT = "ru.shapovalov.bedlam.RECONNECT_VPN"
        const val EXTRA_URI = "uri"
    }
}
