package ru.shapovalov.bedlam.core.latency

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.util.parseHost
import ru.shapovalov.bedlam.core.util.parsePort
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress

@Inject
class PingProfileUseCase(private val context: Context) {

    suspend operator fun invoke(profile: Profile): LatencyResult = withContext(Dispatchers.IO) {
        val address = profile.config.server.address
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

    @Suppress("DEPRECATION")
    private fun underlyingNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 3000
    }
}
