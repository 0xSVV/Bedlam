package ru.shapovalov.bedlam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

class DefaultNetworkListener(
    context: Context,
    private val onChanged: (Network?) -> Unit,
) {
    private val connectivityManager = context.applicationContext
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
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        active = null
    }
}
