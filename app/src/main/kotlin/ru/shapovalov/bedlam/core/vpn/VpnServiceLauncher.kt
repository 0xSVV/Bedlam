package ru.shapovalov.bedlam.core.vpn

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

    fun start(profile: Profile) {
        start(json.encodeToString(profile.config), profile.name)
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

    private fun start(configJson: String, profileName: String) {
        val intent = Intent(appContext, BedlamVpnService::class.java).apply {
            putExtra(BedlamVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(BedlamVpnService.EXTRA_PROFILE_NAME, profileName)
        }
        ContextCompat.startForegroundService(appContext, intent)
    }
}

sealed interface StartActiveProfileResult {
    data object Started : StartActiveProfileResult
    data object NoActiveProfile : StartActiveProfileResult
}
