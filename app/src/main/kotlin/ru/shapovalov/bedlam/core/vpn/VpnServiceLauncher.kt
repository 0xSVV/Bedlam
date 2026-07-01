package ru.shapovalov.bedlam.core.vpn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

@Inject
class VpnServiceLauncher(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val json: Json,
) {
    private val appContext = context.applicationContext

    fun prepareIntent(): Intent? = VpnService.prepare(appContext)

    @Suppress("DEPRECATION")
    fun isServiceRunning(): Boolean {
        val am = appContext.getSystemService(ActivityManager::class.java) ?: return false
        val name = BedlamVpnService::class.java.name
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == name }
    }

    fun start(profile: Profile) {
        start(
            configJson = json.encodeToString(profile.config),
            profileName = profile.name,
            profileId = profile.id,
        )
    }

    suspend fun startActiveProfile(): StartActiveProfileResult {
        val profile = activeProfile() ?: return StartActiveProfileResult.NoActiveProfile
        start(profile)
        return StartActiveProfileResult.Started
    }

    suspend fun activeProfile(): Profile? {
        val activeId = profileRepository.getActiveId() ?: return null
        return profileRepository.get(activeId)
    }

    fun stop() {
        val intent = Intent(appContext, BedlamVpnService::class.java).apply {
            action = BedlamVpnService.ACTION_STOP
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun start(configJson: String, profileName: String, profileId: String?) {
        val intent = Intent(appContext, BedlamVpnService::class.java).apply {
            putExtra(BedlamVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(BedlamVpnService.EXTRA_PROFILE_NAME, profileName)
            putExtra(BedlamVpnService.EXTRA_PROFILE_ID, profileId)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }
}

sealed interface StartActiveProfileResult {
    data object Started : StartActiveProfileResult
    data object NoActiveProfile : StartActiveProfileResult
}
