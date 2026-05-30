package ru.shapovalov.bedlam.core.power.data

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.power.domain.PowerReliabilityRules
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository

private val Context.powerReliabilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "power_reliability",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                produceSharedPreferences = {
                    context.getSharedPreferences(
                        "battery_reliability",
                        Context.MODE_PRIVATE,
                    )
                },
            ),
            SharedPreferencesMigration(
                produceSharedPreferences = {
                    context.getSharedPreferences(
                        "vpn_runtime_state",
                        Context.MODE_PRIVATE,
                    )
                },
            ),
        )
    },
)

@Inject
class PowerReliabilityRepositoryImpl(
    context: Context,
) : PowerReliabilityRepository {
    private val appContext = context.applicationContext
    private val dataStore = appContext.powerReliabilityDataStore

    override val confirmedFingerprint: Flow<String?> =
        dataStore.data.map { prefs -> prefs[KEY_CONFIRMED_FINGERPRINT] }

    override fun snapshotNow(): PowerReliabilitySnapshot =
        buildSnapshot(recentObservedAlwaysOnState = null)

    override fun observeSnapshot(refreshIntervalMillis: Long): Flow<PowerReliabilitySnapshot> =
        flow {
            while (true) {
                emit(snapshotWithStoredState())
                delay(refreshIntervalMillis)
            }
        }

    override suspend fun markConfirmed(fingerprint: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CONFIRMED_FINGERPRINT] = fingerprint
        }
    }

    override suspend fun writeAlwaysOnState(state: AlwaysOnVpnState) {
        dataStore.edit { prefs ->
            prefs[KEY_ALWAYS_ON_STATE] = state.name
            prefs[KEY_ALWAYS_ON_OBSERVED_AT] = System.currentTimeMillis()
        }
    }

    private suspend fun snapshotWithStoredState(): PowerReliabilitySnapshot =
        buildSnapshot(
            recentObservedAlwaysOnState = readAlwaysOnState(
                maxAgeMillis = ALWAYS_ON_OBSERVED_MAX_AGE_MS,
            ),
        )

    private fun buildSnapshot(
        recentObservedAlwaysOnState: AlwaysOnVpnState?,
    ): PowerReliabilitySnapshot {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
        val vendor = detectVendor()
        val batteryUnrestricted =
            powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        val backgroundRestricted = activityManager?.isBackgroundRestricted == true
        val standbyBucket = usageStatsManager?.appStandbyBucket.toStandbyBucket()
        val notificationsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val alwaysOnVpnState = PowerReliabilityRules.effectiveAlwaysOnState(
            secureState = readAlwaysOnVpnStateFromSecure(),
            recentObservedState = recentObservedAlwaysOnState,
        )
        val riskLevel = PowerReliabilityRules.riskLevel(
            vendor = vendor,
            batteryUnrestricted = batteryUnrestricted,
            backgroundRestricted = backgroundRestricted,
            standbyBucket = standbyBucket,
            notificationsAllowed = notificationsAllowed,
        )

        return PowerReliabilitySnapshot(
            vendor = vendor,
            batteryUnrestricted = batteryUnrestricted,
            backgroundRestricted = backgroundRestricted,
            standbyBucket = standbyBucket,
            notificationsAllowed = notificationsAllowed,
            alwaysOnVpnState = alwaysOnVpnState,
            riskLevel = riskLevel,
            buildFingerprint = Build.FINGERPRINT,
        )
    }

    private suspend fun readAlwaysOnState(maxAgeMillis: Long): AlwaysOnVpnState? {
        val prefs = dataStore.data.first()
        val state = prefs[KEY_ALWAYS_ON_STATE]
            ?.let { raw -> AlwaysOnVpnState.entries.firstOrNull { it.name == raw } }
            ?: return null
        val observedAt = prefs[KEY_ALWAYS_ON_OBSERVED_AT] ?: return null
        val ageMillis = System.currentTimeMillis() - observedAt
        return state.takeIf { ageMillis <= maxAgeMillis }
    }

    private fun readAlwaysOnVpnStateFromSecure(): AlwaysOnVpnState? =
        runCatching {
            val alwaysOnPackage = Settings.Secure.getString(
                appContext.contentResolver,
                ALWAYS_ON_VPN_APP,
            ).orEmpty()
            if (alwaysOnPackage.isBlank()) return@runCatching AlwaysOnVpnState.Disabled
            if (alwaysOnPackage != appContext.packageName) {
                return@runCatching AlwaysOnVpnState.OtherVpn
            }

            val lockdownEnabled = Settings.Secure.getInt(
                appContext.contentResolver,
                ALWAYS_ON_VPN_LOCKDOWN,
                0,
            ) == 1
            if (lockdownEnabled) {
                AlwaysOnVpnState.EnabledWithLockdown
            } else {
                AlwaysOnVpnState.Enabled
            }
        }.getOrNull()

    private fun detectVendor(): PowerVendor {
        val maker = listOf(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT)
            .joinToString(separator = " ")
        return PowerVendor.from(maker)
    }

    private fun Int?.toStandbyBucket(): StandbyBucket = when (this) {
        STANDBY_BUCKET_EXEMPTED -> StandbyBucket.Exempted
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.Active
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WorkingSet
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.Frequent
        UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.Rare
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> StandbyBucket.Restricted
        else -> StandbyBucket.Unknown
    }

    companion object {
        private const val STANDBY_BUCKET_EXEMPTED = 5
        private const val ALWAYS_ON_VPN_APP = "always_on_vpn_app"
        private const val ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown"
        private const val ALWAYS_ON_OBSERVED_MAX_AGE_MS = 2 * 60 * 1000L
        private val KEY_CONFIRMED_FINGERPRINT = stringPreferencesKey("confirmed_fingerprint")
        private val KEY_ALWAYS_ON_STATE = stringPreferencesKey("always_on_state")
        private val KEY_ALWAYS_ON_OBSERVED_AT = longPreferencesKey("always_on_observed_at")
    }
}
