package ru.shapovalov.bedlam.core.routing.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.engine.CidrMath
import ru.shapovalov.bedlam.core.routing.engine.LanRanges
import java.net.InetAddress

/**
 * Reads the underlying (non-VPN) network's DNS resolvers. Private-range
 * resolvers are dropped: routed into the tunnel they'd be unreachable from
 * the server, and bypassing the tunnel for them would leak queries.
 */
@Inject
class SystemDnsProvider(private val context: Context) {

    fun publicDnsServers(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        val network = nonVpnNetwork(cm) ?: return emptyList()
        val props = cm.getLinkProperties(network) ?: return emptyList()
        return props.dnsServers.mapNotNull { it.toPublicAddressString() }
    }

    private fun nonVpnNetwork(cm: ConnectivityManager): Network? {
        cm.activeNetwork?.let { active ->
            if (cm.isUsableNonVpn(active)) return active
        }
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.isUsableNonVpn(it) }
    }

    private fun ConnectivityManager.isUsableNonVpn(network: Network): Boolean {
        val caps = getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun InetAddress.toPublicAddressString(): String? {
        if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress) return null
        val bytes = address
        val cidr = when (bytes.size) {
            4 -> Cidr.V4(bytes, 32)
            16 -> Cidr.V6(bytes, 128)
            else -> return null
        }
        val isLan = when (cidr) {
            is Cidr.V4 -> LanRanges.IPV4.any { CidrMath.contains(it, cidr) }
            is Cidr.V6 -> LanRanges.IPV6.any { CidrMath.contains(it, cidr) }
        }
        if (isLan) return null
        return hostAddress?.substringBefore('%')
    }
}
