package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

    private val callback = object : ConnectivityManager.NetworkCallback() {
        private var lastValidated: Boolean? = null

        override fun onAvailable(network: Network) {
            lastValidated = null
            onChanged(network)
        }

        override fun onLost(network: Network) {
            lastValidated = null
            onChanged(null)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val previous = lastValidated
            lastValidated = validated
            if (previous != null && previous != validated) {
                onChanged(network)
            }
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
            connectivityManager.requestNetwork(request, callback)
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
