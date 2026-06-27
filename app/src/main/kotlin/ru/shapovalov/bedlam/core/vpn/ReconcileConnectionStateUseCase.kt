package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.isActiveTunnel

/**
 * Reconciles the volatile, in-memory [HysteriaClient.state] against whether
 * [BedlamVpnService] is actually running. The connection state lives only in
 * process memory; if it claims an active tunnel while the owning service is
 * gone (e.g. the service was killed but the app process survived, or a stale
 * state lingered), the UI would show "connected" with nothing behind it.
 *
 * Run this on app start to force the state back to disconnected in that case.
 * It never fabricates a connected state — a genuinely running service drives
 * the flow to connected on its own.
 */
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
