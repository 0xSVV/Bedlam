package ru.shapovalov.bedlam.core.vpn

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.BedlamApplication
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.appfilter.domain.repository.AppFilterRepository
import ru.shapovalov.bedlam.core.log.AppLog
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
import ru.shapovalov.hysteria.isActiveTunnel
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.TunConfig
import ru.shapovalov.hysteria.config.HysteriaConfig
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: HysteriaClient by injected { hysteriaClient }
    private val appLog: AppLog by injected { appLog }
    private val json: Json by injected { json }
    private val buildRoutePlan: BuildRoutePlanUseCase by injected { buildRoutePlan }
    private val routePlanApplier: RoutePlanApplier by injected { routePlanApplier }
    private val routingRepository: RoutingRepository by injected { routingRepository }
    private val appFilterRepository: AppFilterRepository by injected { appFilterRepository }
    private val profileRepository: ProfileRepository by injected { profileRepository }
    private val runtimeStateRepository: VpnRuntimeStateRepository by injected {
        vpnRuntimeStateRepository
    }
    private val powerReliabilityRepository: PowerReliabilityRepository by injected {
        powerReliabilityRepository
    }

    private lateinit var notifications: VpnNotificationController
    private val selfStopReporter: SelfStopReporter by lazy {
        SelfStopReporter(appLog, runtimeStateRepository) { notifications.postStoppedAlert(it) }
    }
    private val selfStopLock = Any()
    private var networkObserver: UnderlyingNetworkObserver? = null
    private var notificationJob: Job? = null
    private var runtimeHeartbeatJob: Job? = null
    private var livenessKickJob: Job? = null
    private var connectHapticJob: Job? = null

    @Volatile
    private var startJob: Job? = null
    private val startMutex = Mutex()
    private val networkChangeMutex = Mutex()
    private var reconnectWatchdogJob: Job? = null

    @Volatile
    private var reconnectTimeoutJob: Job? = null
    private var settingsWatcherJob: Job? = null
    private var profileNameWatcherJob: Job? = null

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

    @Volatile
    private var stopWasRequested: Boolean = false

    @Volatile
    private var lastStartId: Int = 0

    private val serviceEpoch: Long = System.currentTimeMillis()

    override fun onCreate() {
        super.onCreate()
        notifications = VpnNotificationController(this)
        notifications.createChannels()
        scheduleAlwaysOnVpnStateUpdate()
    }

    override fun onRevoke() {
        appLog.warn(AppLog.SOURCE_VPN, "Android revoked the tunnel: another VPN app took over")
        notifications.postRevokedWarning()
        stop(DisconnectReason.REVOKED)
    }

    override fun onDestroy() {
        persistUnexpectedDestroyIfNeeded()
        startJob?.cancel()
        notificationJob?.cancel()
        runtimeHeartbeatJob?.cancel()
        reconnectWatchdogJob?.cancel()
        reconnectTimeoutJob?.cancel()
        settingsWatcherJob?.cancel()
        profileNameWatcherJob?.cancel()
        livenessKickJob?.cancel()
        connectHapticJob?.cancel()
        networkObserver?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancelReconnectWarning()
        client.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopWasRequested && client.state.value.isActiveTunnel) {
            scope.launch {
                runtimeStateRepository.markInterrupted(serviceEpoch, "Task removed")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if ((application as BedlamApplication).nativeLoadError != null) {
            startAsForeground()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                startAsForeground()
                stop(requestId = intent.getStringExtra(EXTRA_STOP_REQUEST_ID), startId = startId)
                return START_NOT_STICKY
            }

            ACTION_RECONNECT -> {
                startAsForeground()
                appLog.info(AppLog.SOURCE_VPN, "Reconnect requested")
                if (client.stats() == null) {
                    Log.i(TAG, "Reconnect requested with no active session; stopping")
                    stop(startId = startId)
                    return START_NOT_STICKY
                }
                scope.launch {
                    runCatching { client.resetConnections() }
                        .onFailure { Log.w(TAG, "resetConnections failed", it) }
                }
                return START_NOT_STICKY
            }
        }

        stopWasRequested = false

        if (!startAsForeground()) {
            stopOnItsOwn(SelfStop.ForegroundRefused, startId)
            return START_NOT_STICKY
        }
        notifications.cancelStoppedAlert()

        if (startJob?.isActive == true) {
            Log.i(TAG, "Ignoring VPN start while startup is already in progress")
            return START_REDELIVER_INTENT
        }

        connectionName = intent?.getStringExtra(EXTRA_PROFILE_NAME).orEmpty()
        notifications.connectionName = connectionName

        startNetworkObserver()
        startNotificationLoop()
        startRuntimeHeartbeat()
        startReconnectWatchdog()
        startLivenessKick()
        val userInitiated = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
        if (vibratesOnConnect(userInitiated, flags)) startConnectHaptic()

        scheduleAlwaysOnVpnStateUpdate()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            startMutex.withLock {
                val request = when (val resolution = resolveStartRequest(intent)) {
                    is StartResolution.Ready -> resolution.request
                    is StartResolution.Refused -> {
                        stopOnItsOwn(resolution.stop, startId)
                        return@withLock
                    }
                }

                if (client.state.value.isActiveTunnel) {
                    if (currentConfig == request.config) {
                        updateConnectionName(request.profileName)
                        Log.i(TAG, "Ignoring duplicate VPN start while tunnel is active")
                    } else {
                        Log.w(
                            TAG,
                            "Ignoring VPN start for a different config while tunnel is active"
                        )
                    }
                    return@withLock
                }

                updateConnectionName(request.profileName)
                runtimeStateRepository.markStarting(
                    serviceEpoch = serviceEpoch,
                    profileId = request.profileId,
                    profileName = request.profileName,
                )
                startProfileNameWatcher(request.profileId)
                appLog.info(AppLog.SOURCE_VPN, "Starting the tunnel for ${request.profileName}")
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

    private suspend fun resolveStartRequest(intent: Intent?): StartResolution {
        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (!configJson.isNullOrEmpty()) {
            val config = decodeConfig(configJson)
                ?: return StartResolution.Refused(SelfStop.InvalidConfig)
            return StartResolution.Ready(
                StartRequest(
                    config = config,
                    profileId = intent.getStringExtra(EXTRA_PROFILE_ID),
                    profileName = intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty(),
                )
            )
        }

        val profile = profileRepository.getActiveId()?.let { profileRepository.get(it) }
            ?: return StartResolution.Refused(SelfStop.NoActiveProfile)
        return StartResolution.Ready(
            StartRequest(
                config = profile.config,
                profileId = profile.id,
                profileName = profile.name,
            )
        )
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
                tunConfig = plan.toTunConfig(),
                protector = { fd -> protect(fd) },
                tun = { tunConfig -> establishTun(tunConfig) },
            )
            startSettingsWatcher()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "VPN startup failed", e)
            stopOnItsOwn(SelfStop.StartupFailure(e))
        }
    }

    private fun RoutePlan.toTunConfig(): TunConfig =
        TunConfig(mtu = mtu, ipv6Enabled = ipv6Enabled, dns = dnsUpstream)

    private fun startProfileNameWatcher(profileId: String?) {
        profileNameWatcherJob?.cancel()
        profileNameWatcherJob = null
        if (profileId == null) return
        profileNameWatcherJob = scope.launch {
            profileRepository.observe(profileId)
                .mapNotNull { it?.name }
                .distinctUntilChanged()
                .collect { updateConnectionName(it) }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startSettingsWatcher() {
        settingsWatcherJob?.cancel()
        settingsWatcherJob = scope.launch {
            combine(
                routingRepository.observe(),
                appFilterRepository.observe(),
            ) { _, _ -> }
                .debounce(SETTINGS_REAPPLY_DEBOUNCE_MS.milliseconds)
                .collect {
                    if (!client.state.value.isActiveTunnel) return@collect
                    val newPlan = runCatching { buildRoutePlan() }.getOrNull() ?: return@collect
                    if (newPlan == currentRoutePlan) return@collect
                    reapplyTunnel(newPlan)
                }
        }
    }

    private suspend fun reapplyTunnel(plan: RoutePlan) {
        try {
            Log.i(TAG, "Reapplying tunnel after settings change")
            currentRoutePlan = plan
            client.updateTun(plan.toTunConfig()) { tunConfig ->
                establishTun(tunConfig)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Reapply failed", e)
            stopOnItsOwn(SelfStop.ReapplyFailure(e))
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

    private fun stop(
        reason: DisconnectReason = DisconnectReason.USER,
        requestId: String? = null,
        startId: Int = lastStartId,
    ) {
        stopWasRequested = true
        currentConfig = null
        startJob?.cancel()
        startJob = null
        if (reason == DisconnectReason.USER) appLog.info(AppLog.SOURCE_VPN, "Disconnect requested")
        scope.launch(Dispatchers.Main.immediate) {
            runtimeStateRepository.markStopping(serviceEpoch, reason.name, requestId)
            releaseForegroundResources()
            runCatching { client.stop(reason) }
                .onFailure { Log.w(TAG, "client.stop failed", it) }
            runtimeStateRepository.markStopped(reason.name)
            stopSelf(startId)
        }
    }

    private fun stopOnItsOwn(stop: SelfStop, startId: Int? = null) {
        if (!claimSelfStop()) return
        currentConfig = null
        startJob?.cancel()
        startJob = null
        scope.launch(Dispatchers.Main.immediate) {
            releaseForegroundResources()
            selfStopReporter.report(stop)
            runCatching { client.closeSession() }
                .onFailure { Log.w(TAG, "client.closeSession failed", it) }
            if (startId != null) stopSelf(startId) else stopSelf()
        }
    }

    private fun claimSelfStop(): Boolean = synchronized(selfStopLock) {
        if (stopWasRequested) return false
        stopWasRequested = true
        true
    }

    private suspend fun releaseForegroundResources() {
        settingsWatcherJob?.cancel()
        settingsWatcherJob = null
        profileNameWatcherJob?.cancel()
        profileNameWatcherJob = null
        reconnectTimeoutJob?.cancel()
        reconnectTimeoutJob = null
        notificationJob?.cancelAndJoin()
        notificationJob = null
        runtimeHeartbeatJob?.cancelAndJoin()
        runtimeHeartbeatJob = null
        reconnectWatchdogJob?.cancel()
        reconnectWatchdogJob = null
        livenessKickJob?.cancel()
        livenessKickJob = null
        connectHapticJob?.cancel()
        connectHapticJob = null
        networkObserver?.stop()
        networkObserver = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancelReconnectWarning()
        notifications.cancel()
    }

    private fun startAsForeground(): Boolean {
        val notification = notifications.foregroundNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    VpnNotificationController.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(VpnNotificationController.NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start was rejected", e)
            false
        }
    }

    private fun startNetworkObserver() {
        if (networkObserver != null) return
        networkObserver = UnderlyingNetworkObserver(
            context = this,
            scope = scope,
            onAvailable = { network -> setUnderlyingNetworks(network?.let { arrayOf(it) }) },
            onSettledChange = {
                scope.launch { handleUnderlyingNetworkChange() }
            },
            onEvent = { appLog.info(AppLog.SOURCE_VPN, it) },
        ).also { it.start() }
    }

    private suspend fun handleUnderlyingNetworkChange() {
        networkChangeMutex.withLock {
            val newPlan = runCatching { buildRoutePlan() }.getOrNull()
            if (newPlan != null && newPlan != currentRoutePlan) {
                reapplyDnsForNetworkChange(newPlan)
            }
            runCatching { client.resetAfterNetworkChange() }
                .onFailure { Log.w(TAG, "resetConnections failed", it) }
        }
    }

    private suspend fun reapplyDnsForNetworkChange(plan: RoutePlan) {
        val previous = currentRoutePlan
        repeat(TUN_REAPPLY_ATTEMPTS) { attempt ->
            currentRoutePlan = plan
            try {
                client.updateTun(plan.toTunConfig()) { tunConfig ->
                    establishTun(tunConfig)
                }
                return
            } catch (e: CancellationException) {
                currentRoutePlan = previous
                throw e
            } catch (e: Exception) {
                currentRoutePlan = previous
                if (attempt < TUN_REAPPLY_ATTEMPTS - 1) {
                    Log.w(TAG, "DNS reapply after network change failed; retrying", e)
                    delay(TUN_REAPPLY_RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "DNS reapply after network change failed; no interface left", e)
                    stopOnItsOwn(SelfStop.Interruption("DNS reapply after network change failed"))
                }
            }
        }
    }

    private fun startReconnectWatchdog() {
        if (reconnectWatchdogJob != null) return
        reconnectWatchdogJob = scope.launch {
            client.state.collect { state ->
                when (state) {
                    is ConnectionState.Reconnecting -> {
                        if (reconnectTimeoutJob == null) {
                            reconnectTimeoutJob = scope.launch {
                                delay(RECONNECT_WARNING_MS.milliseconds)
                                if (client.state.value is ConnectionState.Reconnecting) {
                                    Log.w(TAG, "Still reconnecting; keeping service alive to retry")
                                    appLog.warn(AppLog.SOURCE_VPN, "Still reconnecting after 3 minutes")
                                    notifications.postReconnectTimeoutWarning()
                                }
                            }
                        }
                    }

                    is ConnectionState.Connected -> {
                        reconnectTimeoutJob?.cancel()
                        reconnectTimeoutJob = null
                        notifications.cancelReconnectWarning()
                    }

                    is ConnectionState.Error -> {
                        if (!stopWasRequested) {
                            Log.w(TAG, "Tunnel failed irrecoverably: ${state.message}")
                            stopOnItsOwn(SelfStop.Failure(state.message))
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startNotificationLoop() {
        notificationJob?.cancel()
        val ticker = screenOnFlow()
            .distinctUntilChanged()
            .flatMapLatest { interactive ->
                if (interactive) {
                    flow {
                        while (true) {
                            emit(Unit)
                            delay(NOTIFICATION_REFRESH_MS)
                        }
                    }
                } else {
                    flowOf(Unit)
                }
            }
        notificationJob = scope.launch(Dispatchers.Default) {
            var prevTx = 0L
            var prevRx = 0L
            var prevAtMillis = SystemClock.elapsedRealtime()
            combine(client.state, ticker) { state, _ -> state }.collect { state ->
                val s = client.stats() ?: HysteriaClient.TrafficStats(0, 0)
                updateAlwaysOnVpnState()
                val now = SystemClock.elapsedRealtime()
                val elapsedMs = (now - prevAtMillis).coerceAtLeast(1L)
                val txRate = ((s.txBytes - prevTx) * 1000 / elapsedMs).coerceAtLeast(0)
                val rxRate = ((s.rxBytes - prevRx) * 1000 / elapsedMs).coerceAtLeast(0)
                prevTx = s.txBytes
                prevRx = s.rxBytes
                prevAtMillis = now
                notifications.post(state, s, txRate, rxRate)
            }
        }
    }

    private fun startLivenessKick() {
        if (livenessKickJob != null) return
        livenessKickJob = scope.launch {
            merge(
                screenOnFlow().distinctUntilChanged().filter { it }.map { },
                deviceIdleExitFlow(),
            ).collect { checkTunnelLiveness("wake") }
        }
    }

    // Only the first Connected of a tunnel: an auto-reconnect can fire at any
    // hour from a pocket, and buzzing for one is noise rather than feedback.
    private fun startConnectHaptic() {
        if (connectHapticJob != null) return
        connectHapticJob = scope.launch {
            client.state.first { it is ConnectionState.Connected }
            vibrateConnected()
        }
    }

    private suspend fun checkTunnelLiveness(source: String) {
        runCatching { client.checkConnection() }
            .onFailure { Log.w(TAG, "Liveness check after $source failed", it) }
    }

    private fun deviceSleepMillis(): Long =
        SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    private fun startRuntimeHeartbeat() {
        if (runtimeHeartbeatJob != null) return
        runtimeHeartbeatJob = scope.launch {
            var lastSleepMillis = deviceSleepMillis()
            while (isActive) {
                runCatching {
                    runtimeStateRepository.heartbeat(serviceEpoch, client.state.value)
                }.onFailure {
                    Log.w(TAG, "Failed to persist VPN runtime heartbeat", it)
                }
                delay(RUNTIME_HEARTBEAT_MS)
                val sleepMillis = deviceSleepMillis()
                val slept = sleepMillis - lastSleepMillis
                lastSleepMillis = sleepMillis
                if (slept >= SLEEP_GAP_CHECK_MS) {
                    Log.i(TAG, "Device slept ${slept}ms; re-checking the tunnel")
                    checkTunnelLiveness("sleep")
                }
            }
        }
    }

    private fun deviceIdleExitFlow(): Flow<Unit> = callbackFlow {
        val power = getSystemService(PowerManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (power?.isDeviceIdleMode == false) trySend(Unit)
            }
        }
        registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
        )
        awaitClose { unregisterReceiver(receiver) }
    }

    private fun screenOnFlow(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(intent?.action == Intent.ACTION_SCREEN_ON)
            }
        }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        trySend(getSystemService(PowerManager::class.java)?.isInteractive ?: true)
        awaitClose { unregisterReceiver(receiver) }
    }

    companion object {
        private const val TAG = "BedlamVpn"
        private const val NOTIFICATION_REFRESH_MS = 1000L
        private const val RECONNECT_WARNING_MS = 3 * 60 * 1000L
        private const val RUNTIME_HEARTBEAT_MS = 30_000L
        private const val SETTINGS_REAPPLY_DEBOUNCE_MS = 500L
        private const val ALWAYS_ON_STATE_REFRESH_MS = 60_000L
        private const val DESTROY_PERSIST_TIMEOUT_MS = 500L
        private const val SLEEP_GAP_CHECK_MS = 20_000L
        private const val TUN_REAPPLY_ATTEMPTS = 2
        private const val TUN_REAPPLY_RETRY_DELAY_MS = 500L
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val ACTION_RECONNECT = "ru.shapovalov.bedlam.RECONNECT_VPN"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_STOP_REQUEST_ID = "stop_request_id"
        const val EXTRA_USER_INITIATED = "user_initiated"
    }

    private suspend fun updateConnectionName(name: String) {
        withContext(Dispatchers.Main.immediate) {
            connectionName = name
            notifications.connectionName = name
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
        val profileId: String?,
        val profileName: String,
    )

    private sealed interface StartResolution {
        data class Ready(val request: StartRequest) : StartResolution
        data class Refused(val stop: SelfStop.Unstartable) : StartResolution
    }

    private fun persistUnexpectedDestroyIfNeeded() {
        if (stopWasRequested || !client.state.value.isActiveTunnel) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(DESTROY_PERSIST_TIMEOUT_MS) {
                    runtimeStateRepository.markInterrupted(serviceEpoch, "Service destroyed")
                } ?: Log.w(TAG, "Timed out persisting unexpected service destroy")
            }
        }.onFailure {
            Log.w(TAG, "Failed to persist unexpected service destroy", it)
        }
    }
}
