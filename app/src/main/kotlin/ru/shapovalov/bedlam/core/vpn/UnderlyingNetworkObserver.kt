package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UnderlyingNetworkObserver(
    context: Context,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onAvailable: (Network?) -> Unit,
    private val onSettledChange: () -> Unit,
    private val onEvent: (String) -> Unit = {},
) {
    private val connectivityManager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var seenInitial = false
    private var debounceJob: Job? = null

    private val listener = DefaultNetworkListener(context) { network ->
        onAvailable(network)
        if (!seenInitial) {
            seenInitial = true
            report("Underlying network: ${describe(network)}")
            return@DefaultNetworkListener
        }
        report("Underlying network changed: ${describe(network)}")
        if (network == null) return@DefaultNetworkListener
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            onSettledChange()
        }
    }

    fun start() = listener.start()

    fun stop() {
        debounceJob?.cancel()
        debounceJob = null
        listener.stop()
    }

    private fun report(message: String) {
        Log.i(TAG, message)
        onEvent(message)
    }

    private fun describe(network: Network?): String {
        if (network == null) return "none"
        val capabilities = runCatching { connectivityManager?.getNetworkCapabilities(network) }.getOrNull()
        val transports = TRANSPORT_NAMES
            .filter { (transport, _) -> capabilities?.hasTransport(transport) == true }
            .map { (_, name) -> name }
            .ifEmpty { listOf("unknown") }
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return "${transports.joinToString("+")}, ${if (validated) "validated" else "not validated"} (id $network)"
    }

    companion object {
        private const val TAG = "UnderlyingNetwork"
        private const val DEFAULT_DEBOUNCE_MS = 500L
        private val TRANSPORT_NAMES = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
        )
    }
}
