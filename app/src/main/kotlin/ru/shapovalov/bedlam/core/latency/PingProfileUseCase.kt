package ru.shapovalov.bedlam.core.latency

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.util.isRealmAddress
import ru.shapovalov.bedlam.core.util.parseHost
import ru.shapovalov.bedlam.core.util.parsePort
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import kotlin.coroutines.resume

@Inject
class PingProfileUseCase(private val context: Context) {

    suspend operator fun invoke(profile: Profile): LatencyResult = withContext(Dispatchers.IO) {
        val address = profile.config.server.address
        if (isRealmAddress(address)) return@withContext LatencyResult.Idle
        val host = parseHost(address)
        val port = parsePort(address)
        measureTcp(underlyingNetwork(), host, port)
    }

    private fun measureTcp(network: Network?, host: String, port: Int): LatencyResult {
        val factory = network?.socketFactory ?: return LatencyResult.Unreachable
        return try {
            factory.createSocket().use { socket ->
                val start = System.currentTimeMillis()
                try {
                    socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                } catch (_: ConnectException) {
                    // RST received — port closed but host responded; RTT is still valid
                } catch (_: IOException) {
                    return@use LatencyResult.Unreachable
                }
                val ms = System.currentTimeMillis() - start
                if (ms < TIMEOUT_MS) LatencyResult.Success(ms) else LatencyResult.Unreachable
            }
        } catch (_: IOException) {
            LatencyResult.Unreachable
        }
    }

    private suspend fun underlyingNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        return withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) cont.resume(network)
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
                runCatching { cm.registerNetworkCallback(request, callback) }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3000
        const val NETWORK_TIMEOUT_MS = 2_000L
    }
}
