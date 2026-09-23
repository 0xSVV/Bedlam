package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.isActiveTunnel

class ReconcileConnectionStateUseCase internal constructor(
    private val client: HysteriaClient,
    private val isServiceRunning: () -> Boolean,
    private val consentRequired: () -> Boolean,
    private val startActiveProfile: suspend () -> StartActiveProfileResult,
    private val runtimeStateRepository: VpnRuntimeStateRepository,
) {
    @Inject
    constructor(
        client: HysteriaClient,
        launcher: VpnServiceLauncher,
        runtimeStateRepository: VpnRuntimeStateRepository,
    ) : this(
        client = client,
        isServiceRunning = launcher::isServiceRunning,
        consentRequired = { launcher.prepareIntent() != null },
        startActiveProfile = launcher::startActiveProfile,
        runtimeStateRepository = runtimeStateRepository,
    )

    suspend operator fun invoke(): ReconcileResult {
        val clientActive = client.state.value.isActiveTunnel
        val running = withContext(Dispatchers.Default) { isServiceRunning() }
        if (clientActive && running) return ReconcileResult.Unchanged
        if (clientActive) {
            runtimeStateRepository.markInterrupted(
                serviceEpoch = runtimeStateRepository.snapshot().serviceEpoch,
                reason = "Client active but VPN service is not running",
            )
            client.shutdown()
        }

        val runtimeState = runtimeStateRepository.snapshot()
        if (!runtimeState.expectsActiveTunnel) {
            return ReconcileResult.Unchanged
        }

        if (consentRequired()) {
            return fail("VPN permission is required")
        }

        return when (startActiveProfile()) {
            StartActiveProfileResult.Started -> ReconcileResult.Restarted
            StartActiveProfileResult.NoActiveProfile -> fail("No active profile")
        }
    }

    private suspend fun fail(reason: String): ReconcileResult {
        runtimeStateRepository.markFailed(reason)
        return ReconcileResult.Failed(reason)
    }
}

sealed interface ReconcileResult {
    data object Unchanged : ReconcileResult
    data object Restarted : ReconcileResult
    data class Failed(val reason: String) : ReconcileResult
}
