package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.isActiveTunnel

@Inject
class ReconcileConnectionStateUseCase(
    private val client: HysteriaClient,
    private val launcher: VpnServiceLauncher,
    private val runtimeStateRepository: VpnRuntimeStateRepository,
) {
    suspend operator fun invoke() {
        val clientActive = client.state.value.isActiveTunnel
        val running = withContext(Dispatchers.Default) { launcher.isServiceRunning() }
        if (clientActive && running) return
        if (clientActive) {
            runtimeStateRepository.markInterrupted(
                serviceEpoch = runtimeStateRepository.snapshot().serviceEpoch,
                reason = "Client active but VPN service is not running",
            )
            client.shutdown()
        }

        val runtimeState = runtimeStateRepository.snapshot()
        if (!runtimeState.expectsActiveTunnel) {
            return
        }

        if (launcher.prepareIntent() != null) {
            runtimeStateRepository.markFailed("VPN permission is required")
            return
        }

        when (launcher.startActiveProfile()) {
            StartActiveProfileResult.Started -> Unit
            StartActiveProfileResult.NoActiveProfile -> {
                runtimeStateRepository.markFailed("No active profile")
            }
        }
    }
}
