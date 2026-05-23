package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Subscribes to the system's default network — whatever Android currently
 * routes non-VPN traffic over — and forwards changes to [onChanged]. Emits
 * `null` when no default exists.
 */
class DefaultNetworkListener(
    context: Context,
    private val onChanged: (Network?) -> Unit,
) {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onChanged(network)
        override fun onLost(network: Network) = onChanged(null)
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
    }
}
