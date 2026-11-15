package ru.shapovalov.bedlam.core.latency

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import kotlin.time.Duration.Companion.seconds
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository
import ru.shapovalov.bedlam.core.routing.engine.DirectDnsPlanner
import ru.shapovalov.bedlam.core.vpn.DefaultNetworkListener
import ru.shapovalov.hysteria.api.DiagnosticResult
import ru.shapovalov.hysteria.api.HysteriaClient

@Inject
class PingProfileUseCase(
    private val context: Context,
    private val client: HysteriaClient,
    private val routingRepository: RoutingRepository,
) {
    suspend operator fun invoke(profile: Profile): LatencyResult = coroutineScope {
        val selected = CompletableDeferred<Network>()
        val changed = CompletableDeferred<Unit>()
        var first: Network? = null
        val listener = DefaultNetworkListener(context) { network ->
            if (!selected.isCompleted && (network != null)) {
                first = network
                selected.complete(network)
            } else if (selected.isCompleted && (network != first)) {
                changed.complete(Unit)
            }
        }
        try {
            listener.start()
            val network = withTimeoutOrNull(2.seconds) { selected.await() }
                ?: return@coroutineScope LatencyResult.NoNetwork
            val measurement = async {
                withContext(Dispatchers.IO) {
                    val cm = context.getSystemService(ConnectivityManager::class.java)
                    val servers = cm.getLinkProperties(network)?.dnsServers
                        ?.mapNotNull { it.hostAddress }.orEmpty()
                    val dns = DirectDnsPlanner.plan(routingRepository.get(), servers)
                    when (val result = client.measureLatency(profile.config, dns, network.protector())) {
                        is DiagnosticResult.Ok -> LatencyResult.Success(result.rttMillis)
                        is DiagnosticResult.Error -> when (result.reason) {
                            "timeout" -> LatencyResult.TimedOut
                            "connection" -> LatencyResult.ConnectionFailed
                            else -> LatencyResult.Unreachable
                        }
                    }
                }
            }
            select {
                measurement.onAwait { it }
                changed.onAwait {
                    measurement.cancel()
                    LatencyResult.NoNetwork
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LatencyResult.Unreachable
        } finally {
            listener.stop()
        }
    }

    private fun Network.protector() = object : HysteriaClient.SocketProtector {
        override fun protect(fd: Int): Boolean = bindAndProtect(fd)
        override fun bindAndProtect(fd: Int): Boolean = runCatching {
            // Binding to an explicit physical network bypasses VPN routing.
            // Duplicate the descriptor so Kotlin never closes Go's socket.
            ParcelFileDescriptor.fromFd(fd).use { bindSocket(it.fileDescriptor) }
            true
        }.getOrDefault(defaultValue = false)
    }
}
