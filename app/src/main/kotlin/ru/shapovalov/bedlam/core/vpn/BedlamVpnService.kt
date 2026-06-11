package ru.shapovalov.bedlam.core.vpn

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
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
    private val routingRepository: RoutingRepository by injected { routingRepository }
    private val appFilterRepository: AppFilterRepository by injected { appFilterRepository }
    private val profileRepository: ProfileRepository by injected { profileRepository }
    private val powerReliabilityRepository: PowerReliabilityRepository by injected {
        powerReliabilityRepository
    }

    private lateinit var notifications: VpnNotificationController
    private lateinit var wakeLock: WakeLockHolder
    private var networkObserver: UnderlyingNetworkObserver? = null
    private var notificationJob: Job? = null

    @Volatile
    private var startJob: Job? = null
    private val startMutex = Mutex()
    private var reconnectWatchdogJob: Job? = null
    @Volatile
    private var reconnectTimeoutJob: Job? = null
    private var settingsWatcherJob: Job? = null

    @Volatile
    private var currentRoutePlan: RoutePlan? = null
    @Volatile
    private var currentConfig: HysteriaConfig? = null
    @Volatile
    private var connectionName: String = ""
    @Volatile
    private var lastAlwaysOnVpnState: AlwaysOnVpnState? = null
    @Volatile
    private var lastAlwaysOnVpnStateWriteMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        notifications = VpnNotificationController(this)
        wakeLock = WakeLockHolder(this, scope)
        notifications.createChannel()
        scheduleAlwaysOnVpnStateUpdate()
    }

    override fun onRevoke() = stop(DisconnectReason.REVOKED)

    override fun onDestroy() {
        startJob?.cancel()
        notificationJob?.cancel()
        reconnectWatchdogJob?.cancel()
        reconnectTimeoutJob?.cancel()
        settingsWatcherJob?.cancel()
        networkObserver?.stop()
        wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startAsForeground()
                stop()
                return START_NOT_STICKY
            }

            ACTION_RECONNECT -> {
                startAsForeground()
                if (!client.state.value.isActiveTunnel()) {
                    Log.i(TAG, "Reconnect requested with no active tunnel; stopping")
                    stop()
                    return START_NOT_STICKY
                }
                scope.launch {
                    runCatching { client.resetConnections() }
                        .onFailure { Log.w(TAG, "resetConnections failed", it) }
                }
                return START_NOT_STICKY
            }
        }

        startAsForeground()

        if (startJob?.isActive == true) {
            Log.i(TAG, "Ignoring VPN start while startup is already in progress")
            return START_REDELIVER_INTENT
        }

        connectionName = intent?.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        notifications.connectionName = connectionName

        wakeLock.acquire()
        startNetworkObserver()
        startNotificationLoop()
        startReconnectWatchdog()

        scheduleAlwaysOnVpnStateUpdate()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            startMutex.withLock {
                val request = resolveStartRequest(intent)
                if (request == null) {
                    Log.e(TAG, "No VPN config provided and no active profile is saved")
                    releaseAndStopSelf(startId)
                    return@withLock
                }

                if (client.state.value.isActiveTunnel()) {
                    if (currentConfig == request.config) {
                        updateConnectionName(request.profileName)
                        Log.i(TAG, "Ignoring duplicate VPN start while tunnel is active")
                    } else {
                        Log.w(TAG, "Ignoring VPN start for a different config while tunnel is active")
                    }
                    return@withLock
                }

                updateConnectionName(request.profileName)
                launchTunnel(request.config)
            }
        }
        startJob = job
        job.invokeOnCompletion {
            if (startJob === job) startJob = null
        }
        job.start()
        return START_REDELIVER_INTENT
    }

    private suspend fun resolveStartRequest(intent: Intent?): StartRequest? {
        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (!configJson.isNullOrEmpty()) {
            val config = decodeConfig(configJson) ?: return null
            return StartRequest(
                config = config,
                profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty(),
            )
        }

        val activeId = profileRepository.getActiveId() ?: return null
        val profile = profileRepository.get(activeId) ?: return null
        return StartRequest(config = profile.config, profileName = profile.name)
    }

    private fun decodeConfig(configJson: String): HysteriaConfig? {
        return try {
            json.decodeFromString<HysteriaConfig>(configJson)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid config JSON", e)
            null
        }
    }

    private suspend fun launchTunnel(config: HysteriaConfig) {
        currentConfig = config
        try {
            val plan = buildRoutePlan()
            currentRoutePlan = plan
            client.start(
                config = config,
                tunConfig = TunConfig(ipv6Enabled = plan.ipv6Enabled),
                protector = { fd -> protect(fd) },
                tun = { tunConfig -> establishTun(tunConfig) },
            )
            startSettingsWatcher()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "VPN startup failed", e)
            if (client.state.value is ConnectionState.Error) {
                releaseAndStopSelf()
            } else {
                stopAfterStartFailure()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startSettingsWatcher() {
        settingsWatcherJob?.cancel()
        settingsWatcherJob = scope.launch {
            var lastPlan = currentRoutePlan
            combine(
                routingRepository.observe(),
                appFilterRepository.observe(),
            ) { _, _ -> Unit }
                .debounce(SETTINGS_REAPPLY_DEBOUNCE_MS)
                .collect {
                    val newPlan = runCatching { buildRoutePlan() }.getOrNull() ?: return@collect
                    if (newPlan == lastPlan) return@collect

                    val settledState = withTimeoutOrNull(CONNECT_SETTLE_TIMEOUT_MS) {
                        client.state.first {
                            it is ConnectionState.Connected ||
                                it is ConnectionState.Disconnected ||
                                it is ConnectionState.Error
                        }
                    } ?: return@collect

                    if (settledState !is ConnectionState.Connected) return@collect

                    lastPlan = newPlan
                    reapplyTunnel(newPlan)
                }
        }
    }

    private suspend fun reapplyTunnel(plan: RoutePlan) {
        try {
            Log.i(TAG, "Reapplying tunnel after settings change")
            currentRoutePlan = plan
            client.updateTun(TunConfig(ipv6Enabled = plan.ipv6Enabled)) { tunConfig ->
                establishTun(tunConfig)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reapply failed", e)
            stop()
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
        currentConfig = null
        startJob?.cancel()
        startJob = null
        scope.launch(Dispatchers.Main.immediate) {
            releaseForegroundResources()
            runCatching { client.stop(reason) }
                .onFailure { Log.w(TAG, "client.stop failed", it) }
            stopSelf()
        }
    }

    private fun releaseForegroundResources() {
        settingsWatcherJob?.cancel()
        settingsWatcherJob = null
        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = null
        notificationJob?.cancel()
        notificationJob = null
        reconnectWatchdogJob?.cancel()
        reconnectWatchdogJob = null
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

    private fun startReconnectWatchdog() {
        if (reconnectWatchdogJob != null) return
        reconnectWatchdogJob = scope.launch {
            client.state.collect { state ->
                when (state) {
                    is ConnectionState.Reconnecting -> {
                        if (reconnectTimeoutJob == null) {
                            reconnectTimeoutJob = scope.launch {
                                delay(RECONNECT_TIMEOUT_MS)
                                if (client.state.value is ConnectionState.Reconnecting) {
                                    Log.w(TAG, "Reconnect timed out, stopping service")
                                    notifications.postReconnectTimeoutWarning()
                                    stop()
                                }
                            }
                        }
                    }
                    is ConnectionState.Connected -> {
                        reconnectTimeoutJob?.cancel()
                        reconnectTimeoutJob = null
                    }
                    else -> Unit
                }
            }
        }
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
                updateAlwaysOnVpnState()
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
        private const val RECONNECT_TIMEOUT_MS = 3 * 60 * 1000L
        private const val SETTINGS_REAPPLY_DEBOUNCE_MS = 500L
        private const val CONNECT_SETTLE_TIMEOUT_MS = 5_000L
        private const val ALWAYS_ON_STATE_REFRESH_MS = 30_000L
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val ACTION_RECONNECT = "ru.shapovalov.bedlam.RECONNECT_VPN"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_PROFILE_NAME = "profile_name"
    }

    private suspend fun updateConnectionName(name: String) {
        withContext(Dispatchers.Main.immediate) {
            connectionName = name
            notifications.connectionName = name
        }
    }

    private suspend fun releaseAndStopSelf(startId: Int? = null) {
        withContext(Dispatchers.Main.immediate) {
            releaseForegroundResources()
            if (startId != null) stopSelf(startId) else stopSelf()
        }
    }

    private suspend fun stopAfterStartFailure() {
        currentConfig = null
        withContext(Dispatchers.Main.immediate) {
            releaseForegroundResources()
        }
        runCatching { client.stop() }
            .onFailure { Log.w(TAG, "client.stop failed", it) }
        withContext(Dispatchers.Main.immediate) {
            stopSelf()
        }
    }

    private fun scheduleAlwaysOnVpnStateUpdate() {
        scope.launch { updateAlwaysOnVpnState() }
    }

    private suspend fun updateAlwaysOnVpnState() {
        val state = withContext(Dispatchers.Main.immediate) {
            when {
                !isAlwaysOn -> AlwaysOnVpnState.Disabled
                isLockdownEnabled -> AlwaysOnVpnState.EnabledWithLockdown
                else -> AlwaysOnVpnState.Enabled
            }
        }
        val now = System.currentTimeMillis()
        if (
            state == lastAlwaysOnVpnState &&
            now - lastAlwaysOnVpnStateWriteMillis < ALWAYS_ON_STATE_REFRESH_MS
        ) {
            return
        }

        runCatching { powerReliabilityRepository.writeAlwaysOnState(state) }
            .onSuccess {
                lastAlwaysOnVpnState = state
                lastAlwaysOnVpnStateWriteMillis = now
            }
            .onFailure { Log.w(TAG, "Failed to persist Always-on VPN state", it) }
    }

    private data class StartRequest(
        val config: HysteriaConfig,
        val profileName: String,
    )
}

private fun ConnectionState.isActiveTunnel(): Boolean =
    this is ConnectionState.Connecting ||
        this is ConnectionState.Connected ||
        this is ConnectionState.Reconnecting
