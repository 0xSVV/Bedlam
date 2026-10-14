package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Tracks the best underlying (non-VPN) network. A plain default-network
 * callback would report the VPN itself once the tunnel is up, leaving
 * Wi-Fi ↔ cellular handoffs invisible to the service.
 */
class DefaultNetworkListener(
    context: Context,
    private val onChanged: (Network?) -> Unit,
) {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private data class Candidate(val caps: NetworkCapabilities, val links: LinkProperties?)
    private val candidates = linkedMapOf<Network, Candidate>()
    @Volatile private var selected: Network? = null
    private var selectedDns: List<String> = emptyList()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
            if (caps != null) candidates[network] = Candidate(caps, connectivityManager.getLinkProperties(network))
            selectAndPublish()
        }

        override fun onLost(network: Network) {
            candidates.remove(network)
            selectAndPublish()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val previous = candidates[network]
            candidates[network] = Candidate(caps, previous?.links)
            selectAndPublish()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val previous = candidates[network] ?: return
            candidates[network] = previous.copy(links = linkProperties)
            selectAndPublish()
        }
    }

    fun selectedNetwork(): Network? = selected

    private fun selectAndPublish() {
        candidates.entries.removeAll { (_, value) ->
            !value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val previous = selected
        val active = connectivityManager.activeNetwork?.takeIf { network ->
            candidates[network]?.caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }
        val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // This callback is registered with registerBestMatchingNetworkCallback.
            candidates.keys.firstOrNull()
        } else {
            val selectorCandidates = candidates.map { (network, value) ->
                UnderlyingNetworkSelector.Candidate(
                    id = network.networkHandle.toString(),
                    validated = value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    transport = when {
                        value.caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> UnderlyingNetworkSelector.Transport.ETHERNET
                        value.caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UnderlyingNetworkSelector.Transport.WIFI
                        value.caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UnderlyingNetworkSelector.Transport.CELLULAR
                        else -> UnderlyingNetworkSelector.Transport.OTHER
                    },
                )
            }
            val activeId = active?.networkHandle?.toString()
            val previousId = previous?.networkHandle?.toString()
            val selectedId = UnderlyingNetworkSelector.select(selectorCandidates, activeId, previousId)
            candidates.keys.firstOrNull { it.networkHandle.toString() == selectedId }
        }
        val dns = chosen?.let { candidates[it]?.links?.dnsServers?.map { address -> address.hostAddress.orEmpty() } }.orEmpty()
        if (chosen != previous || dns != selectedDns) {
            selected = chosen
            selectedDns = dns
            onChanged(chosen)
        }
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            connectivityManager.registerBestMatchingNetworkCallback(
                request,
                callback,
                Handler(Looper.getMainLooper()),
            )
        } else {
            connectivityManager.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        }
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
    }
}
