package ru.shapovalov.bedlam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/**
 * Tracks the underlying (non-VPN) default network and invokes [onChanged]
 * whenever it changes. Intentionally avoids `registerDefaultNetworkCallback`
 * on API 29+ because that returns the VPN itself.
 */
class DefaultNetworkListener(
    context: Context,
    private val onChanged: (Network?) -> Unit,
) {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var active: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            update(network)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            update(network)
        }

        override fun onLost(network: Network) {
            if (active == network) {
                active = null
                onChanged(null)
            }
        }
    }

    private fun update(network: Network) {
        if (active == network) return
        active = network
        onChanged(network)
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cm.registerBestMatchingNetworkCallback(request, callback, android.os.Handler(android.os.Looper.getMainLooper()))
        } else {
            cm.requestNetwork(request, callback)
        }
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        active = null
    }
}
