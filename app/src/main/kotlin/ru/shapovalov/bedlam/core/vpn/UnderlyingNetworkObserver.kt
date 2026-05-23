package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.net.Network
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
) {
    private var seenInitial = false
    private var debounceJob: Job? = null

    private val listener = DefaultNetworkListener(context) { network ->
        onAvailable(network)
        if (!seenInitial) {
            seenInitial = true
            Log.i(TAG, "Initial underlying network: $network")
            return@DefaultNetworkListener
        }
        Log.i(TAG, "Underlying network changed: $network")
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

    companion object {
        private const val TAG = "UnderlyingNetwork"
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
