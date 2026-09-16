package ru.shapovalov.bedlam.core.vpn

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.isActiveTunnel

class ReconnectProfileUseCase internal constructor(
    private val clientState: StateFlow<ConnectionState>,
    private val runtimeState: Flow<VpnRuntimeState>,
    private val consentRequired: () -> Boolean,
    private val stopTunnel: () -> Unit,
    private val startTunnel: (Profile) -> Unit,
    private val loadProfile: suspend (String) -> Profile?,
) {
    @Inject
    constructor(
        client: HysteriaClient,
        launcher: VpnServiceLauncher,
        runtimeStateRepository: VpnRuntimeStateRepository,
        profileRepository: ProfileRepository,
    ) : this(
        clientState = client.state,
        runtimeState = runtimeStateRepository.state,
        consentRequired = { launcher.prepareIntent() != null },
        stopTunnel = launcher::stop,
        startTunnel = launcher::start,
        loadProfile = profileRepository::get,
    )

    suspend fun isTunnelUsing(profileId: String): Boolean =
        clientState.value.isActiveTunnel && runtimeState.first().profileId == profileId

    suspend operator fun invoke(profileId: String) {
        withContext(NonCancellable) {
            if (!isTunnelUsing(profileId) || consentRequired()) return@withContext
            stopTunnel()
            val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                combine(clientState, runtimeState) { state, runtime ->
                    !state.isActiveTunnel && runtime.status == VpnRuntimeStatus.Stopped
                }.first { it }
            }
            if (stopped == null) {
                Log.w(TAG, "Tunnel did not stop within $STOP_TIMEOUT_MS ms, not restarting")
                return@withContext
            }
            val profile = loadProfile(profileId) ?: return@withContext
            startTunnel(profile)
        }
    }

    private companion object {
        const val TAG = "ReconnectProfile"
        const val STOP_TIMEOUT_MS = 30_000L
    }
}
