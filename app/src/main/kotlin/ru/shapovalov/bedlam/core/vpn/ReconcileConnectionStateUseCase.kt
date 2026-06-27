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
) {
    suspend operator fun invoke() {
        if (!client.state.value.isActiveTunnel) return
        val running = withContext(Dispatchers.Default) { launcher.isServiceRunning() }
        if (!running) client.shutdown()
    }
}
