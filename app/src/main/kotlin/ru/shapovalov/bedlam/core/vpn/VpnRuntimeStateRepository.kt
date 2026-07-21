package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.hysteria.ConnectionState

private val Context.vpnRuntimeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vpn_runtime_state",
)

@Inject
class VpnRuntimeStateRepository(
    context: Context,
) {
    private val dataStore = context.applicationContext.vpnRuntimeDataStore

    val state: Flow<VpnRuntimeState> = dataStore.data.map { prefs -> prefs.toRuntimeState() }

    suspend fun snapshot(): VpnRuntimeState = state.first()

    suspend fun markStarting(
        serviceEpoch: Long,
        profileId: String?,
        profileName: String,
    ) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_DESIRED_RUNNING] = true
            prefs[KEY_STATUS] = VpnRuntimeStatus.Starting.name
            prefs[KEY_SERVICE_EPOCH] = serviceEpoch
            prefs[KEY_PROFILE_NAME] = profileName
            prefs[KEY_STARTED_AT] = now
            prefs[KEY_HEARTBEAT_AT] = now
            prefs.remove(KEY_STOPPED_AT)
            prefs.remove(KEY_CONNECTED_SINCE)
            prefs.remove(KEY_LAST_STOP_REASON)
            prefs.remove(KEY_LAST_ERROR)
            if (profileId == null) {
                prefs.remove(KEY_PROFILE_ID)
            } else {
                prefs[KEY_PROFILE_ID] = profileId
            }
        }
    }

    suspend fun heartbeat(serviceEpoch: Long, clientState: ConnectionState) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_HEARTBEAT_AT] = now
            prefs[KEY_SERVICE_EPOCH] = serviceEpoch
            when (clientState) {
                ConnectionState.Connecting -> {
                    prefs[KEY_DESIRED_RUNNING] = true
                    prefs[KEY_STATUS] = VpnRuntimeStatus.Starting.name
                }

                is ConnectionState.Connected -> {
                    prefs[KEY_DESIRED_RUNNING] = true
                    prefs[KEY_STATUS] = VpnRuntimeStatus.Running.name
                    prefs[KEY_CONNECTED_SINCE] = clientState.connectedSinceMillis
                    prefs.remove(KEY_LAST_ERROR)
                }

                is ConnectionState.Reconnecting -> {
                    prefs[KEY_DESIRED_RUNNING] = true
                    prefs[KEY_STATUS] = VpnRuntimeStatus.Running.name
                }

                is ConnectionState.Error -> {
                    prefs[KEY_STATUS] = VpnRuntimeStatus.Failed.name
                    prefs[KEY_LAST_ERROR] = clientState.message
                }

                is ConnectionState.Disconnected -> Unit
            }
        }
    }

    suspend fun markStopping(serviceEpoch: Long, reason: String) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_DESIRED_RUNNING] = false
            prefs[KEY_STATUS] = VpnRuntimeStatus.Stopping.name
            prefs[KEY_SERVICE_EPOCH] = serviceEpoch
            prefs[KEY_HEARTBEAT_AT] = now
            prefs[KEY_LAST_STOP_REASON] = reason
        }
    }

    suspend fun markStopped(reason: String) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_DESIRED_RUNNING] = false
            prefs[KEY_STATUS] = VpnRuntimeStatus.Stopped.name
            prefs[KEY_HEARTBEAT_AT] = now
            prefs[KEY_STOPPED_AT] = now
            prefs[KEY_LAST_STOP_REASON] = reason
            prefs.remove(KEY_CONNECTED_SINCE)
        }
    }

    suspend fun markInterrupted(serviceEpoch: Long, reason: String) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_DESIRED_RUNNING] = true
            prefs[KEY_STATUS] = VpnRuntimeStatus.Interrupted.name
            prefs[KEY_SERVICE_EPOCH] = serviceEpoch
            prefs[KEY_HEARTBEAT_AT] = now
            prefs[KEY_STOPPED_AT] = now
            prefs[KEY_LAST_ERROR] = reason
        }
    }

    suspend fun markFailed(reason: String) {
        val now = System.currentTimeMillis()
        dataStore.edit { prefs ->
            prefs[KEY_DESIRED_RUNNING] = false
            prefs[KEY_STATUS] = VpnRuntimeStatus.Failed.name
            prefs[KEY_HEARTBEAT_AT] = now
            prefs[KEY_STOPPED_AT] = now
            prefs[KEY_LAST_ERROR] = reason
            prefs.remove(KEY_CONNECTED_SINCE)
        }
    }

    private fun Preferences.toRuntimeState(): VpnRuntimeState =
        VpnRuntimeState(
            desiredRunning = this[KEY_DESIRED_RUNNING] ?: false,
            status = this[KEY_STATUS].toStatus(),
            profileId = this[KEY_PROFILE_ID],
            profileName = this[KEY_PROFILE_NAME].orEmpty(),
            serviceEpoch = this[KEY_SERVICE_EPOCH] ?: 0L,
            startedAtMillis = this[KEY_STARTED_AT],
            connectedSinceMillis = this[KEY_CONNECTED_SINCE],
            heartbeatAtMillis = this[KEY_HEARTBEAT_AT] ?: 0L,
            stoppedAtMillis = this[KEY_STOPPED_AT],
            lastStopReason = this[KEY_LAST_STOP_REASON],
            lastError = this[KEY_LAST_ERROR],
        )

    private fun String?.toStatus(): VpnRuntimeStatus =
        VpnRuntimeStatus.entries.firstOrNull { it.name == this } ?: VpnRuntimeStatus.Stopped

    companion object {
        private val KEY_DESIRED_RUNNING = booleanPreferencesKey("desired_running")
        private val KEY_STATUS = stringPreferencesKey("status")
        private val KEY_PROFILE_ID = stringPreferencesKey("profile_id")
        private val KEY_PROFILE_NAME = stringPreferencesKey("profile_name")
        private val KEY_SERVICE_EPOCH = longPreferencesKey("service_epoch")
        private val KEY_STARTED_AT = longPreferencesKey("started_at")
        private val KEY_CONNECTED_SINCE = longPreferencesKey("connected_since")
        private val KEY_HEARTBEAT_AT = longPreferencesKey("heartbeat_at")
        private val KEY_STOPPED_AT = longPreferencesKey("stopped_at")
        private val KEY_LAST_STOP_REASON = stringPreferencesKey("last_stop_reason")
        private val KEY_LAST_ERROR = stringPreferencesKey("last_error")
    }
}

data class VpnRuntimeState(
    val desiredRunning: Boolean = false,
    val status: VpnRuntimeStatus = VpnRuntimeStatus.Stopped,
    val profileId: String? = null,
    val profileName: String = "",
    val serviceEpoch: Long = 0L,
    val startedAtMillis: Long? = null,
    val connectedSinceMillis: Long? = null,
    val heartbeatAtMillis: Long = 0L,
    val stoppedAtMillis: Long? = null,
    val lastStopReason: String? = null,
    val lastError: String? = null,
) {
    val expectsActiveTunnel: Boolean
        get() = desiredRunning && status in RECOVERABLE_STATUSES

    fun isHeartbeatFresh(nowMillis: Long): Boolean =
        heartbeatAtMillis > 0L && nowMillis - heartbeatAtMillis <= HEARTBEAT_STALE_AFTER_MS

    companion object {
        const val HEARTBEAT_STALE_AFTER_MS = 60_000L

        private val RECOVERABLE_STATUSES = setOf(
            VpnRuntimeStatus.Starting,
            VpnRuntimeStatus.Running,
            VpnRuntimeStatus.Interrupted,
        )
    }
}

enum class VpnRuntimeStatus {
    Stopped,
    Starting,
    Running,
    Stopping,
    Interrupted,
    Failed,
}

fun ConnectionState.effectiveWith(
    runtimeState: VpnRuntimeState,
    nowMillis: Long = System.currentTimeMillis(),
): ConnectionState =
    if (isRuntimeRecoveryPending(runtimeState, nowMillis)) ConnectionState.Connecting else this

private fun ConnectionState.isRuntimeRecoveryPending(
    runtimeState: VpnRuntimeState,
    nowMillis: Long,
): Boolean =
    this is ConnectionState.Disconnected &&
        runtimeState.expectsActiveTunnel &&
        runtimeState.isHeartbeatFresh(nowMillis)
