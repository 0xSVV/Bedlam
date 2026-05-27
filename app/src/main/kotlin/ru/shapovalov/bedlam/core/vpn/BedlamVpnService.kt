package ru.shapovalov.bedlam.core.vpn

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import ru.shapovalov.bedlam.core.routing.domain.usecase.BuildRoutePlanUseCase
import ru.shapovalov.bedlam.core.routing.engine.RoutePlanApplier
import ru.shapovalov.bedlam.core.vpn.notification.VpnNotificationController
import ru.shapovalov.bedlam.di.injected
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.TunConfig
import ru.shapovalov.hysteria.config.HysteriaConfig

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: HysteriaClient by injected { hysteriaClient }
    private val json: Json by injected { json }
    private val buildRoutePlan: BuildRoutePlanUseCase by injected { buildRoutePlan }
    private val routePlanApplier: RoutePlanApplier by injected { routePlanApplier }

    private lateinit var notifications: VpnNotificationController
    private lateinit var wakeLock: WakeLockHolder
    private var networkObserver: UnderlyingNetworkObserver? = null
    private var notificationJob: Job? = null

    @Volatile
    private var currentRoutePlan: RoutePlan? = null
    @Volatile
    private var connectionName: String = ""

    override fun onCreate() {
        super.onCreate()
        notifications = VpnNotificationController(this)
        wakeLock = WakeLockHolder(this, scope)
        notifications.createChannel()
    }

    override fun onRevoke() = stop(DisconnectReason.REVOKED)

    override fun onDestroy() {
        notificationJob?.cancel()
        networkObserver?.stop()
        wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }

            ACTION_RECONNECT -> {
                scope.launch {
                    runCatching { client.resetConnections() }
                        .onFailure { Log.w(TAG, "resetConnections failed", it) }
                }
                return START_NOT_STICKY
            }
        }

        val config = parseConfig(intent) ?: return START_NOT_STICKY
        connectionName = intent?.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        notifications.connectionName = connectionName

        startAsForeground()
        wakeLock.acquire()
        startNetworkObserver()
        startNotificationLoop()
        launchTunnel(config)
        return START_NOT_STICKY
    }

    private fun parseConfig(intent: Intent?): HysteriaConfig? {
        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (configJson.isNullOrEmpty()) {
            Log.e(TAG, "No config provided")
            stopSelf()
            return null
        }
        return try {
            json.decodeFromString<HysteriaConfig>(configJson)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid config JSON", e)
            stopSelf()
            null
        }
    }

    private fun launchTunnel(config: HysteriaConfig) {
        scope.launch {
            try {
                currentRoutePlan = buildRoutePlan()
                client.start(
                    config = config,
                    tunConfig = TunConfig.Default,
                    protector = { fd -> protect(fd) },
                    tun = { tunConfig -> establishTun(tunConfig) },
                )
            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                if (client.state.value is ConnectionState.Error) {
                    releaseForegroundResources()
                    stopSelf()
                } else {
                    stop()
                }
            }
        }
    }

    private fun establishTun(tunConfig: TunConfig): ParcelFileDescriptor {
        val plan = currentRoutePlan
            ?: throw IllegalStateException("RoutePlan not built before establishTun()")
        val builder = Builder()
            .setSession(connectionName.ifEmpty { getString(R.string.vpn_session_default) })
            .setMtu(tunConfig.mtu)
            .setMetered(false)
            .addAddress(TunConfig.IPV4_ADDRESS, TunConfig.IPV4_PREFIX_LENGTH)
            .addAddress(TunConfig.IPV6_ADDRESS, TunConfig.IPV6_PREFIX_LENGTH)
        routePlanApplier.apply(plan, builder)
        return builder.establish()
            ?: throw IllegalStateException("VpnService.establish() returned null")
    }

    private fun stop(reason: DisconnectReason = DisconnectReason.USER) {
        releaseForegroundResources()
        scope.launch {
            runCatching { client.stop(reason) }
                .onFailure { Log.w(TAG, "client.stop failed", it) }
            stopSelf()
        }
    }

    private fun releaseForegroundResources() {
        notificationJob?.cancel()
        notificationJob = null
        networkObserver?.stop()
        networkObserver = null
        wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel()
    }

    private fun startAsForeground() {
        val notification = notifications.foregroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotificationController.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(VpnNotificationController.NOTIFICATION_ID, notification)
        }
    }

    private fun startNetworkObserver() {
        if (networkObserver != null) return
        networkObserver = UnderlyingNetworkObserver(
            context = this,
            scope = scope,
            onAvailable = { network -> setUnderlyingNetworks(network?.let { arrayOf(it) }) },
            onSettledChange = {
                scope.launch {
                    runCatching { client.resetConnections() }
                        .onFailure { Log.w(TAG, "resetConnections failed", it) }
                }
            },
        ).also { it.start() }
    }

    private fun startNotificationLoop() {
        notificationJob?.cancel()
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(NOTIFICATION_REFRESH_MS)
            }
        }
        notificationJob = scope.launch(Dispatchers.Default) {
            var prevTx = 0L
            var prevRx = 0L
            combine(client.state, ticker) { state, _ -> state }.collect { state ->
                val s = client.stats() ?: HysteriaClient.TrafficStats(0, 0)
                val txRate = (s.txBytes - prevTx).coerceAtLeast(0)
                val rxRate = (s.rxBytes - prevRx).coerceAtLeast(0)
                prevTx = s.txBytes
                prevRx = s.rxBytes
                notifications.post(state, s, txRate, rxRate)
            }
        }
    }

    companion object {
        private const val TAG = "BedlamVpn"
        private const val NOTIFICATION_REFRESH_MS = 1000L
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val ACTION_RECONNECT = "ru.shapovalov.bedlam.RECONNECT_VPN"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_PROFILE_NAME = "profile_name"
    }
}
