package ru.shapovalov.bedlam

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.util.Log
import golib.Golib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tunActive = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (configJson.isNullOrEmpty()) {
            Log.e(TAG, "No config provided")
            stopSelf()
            return START_NOT_STICKY
        }

        registerProtector()

        scope.launch {
            try {
                connectAndStartTun(configJson)
            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                stop()
            }
        }

        return START_STICKY
    }

    private fun registerProtector() {
        Golib.setFdProtector { fd -> this@BedlamVpnService.protect(fd) }
    }

    private fun connectAndStartTun(configJson: String) {
        Golib.startClient(configJson, object : golib.EventHandler {
            override fun onConnected(udpEnabled: Boolean) {
                Log.i(TAG, "Hysteria connected (UDP=$udpEnabled)")
            }

            override fun onDisconnected(reason: String) {
                Log.i(TAG, "Hysteria disconnected: $reason")
            }

            override fun onError(message: String) {
                Log.e(TAG, "Hysteria error: $message")
            }
        })

        createTunnel()
    }

    private fun createTunnel() {
        val builder = Builder()
            .setSession("Bedlam")
            .setMtu(MTU)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")

        val pfd = builder.establish()
            ?: throw IllegalStateException("VpnService.establish() returned null")

        val rawFd = pfd.detachFd()
        Golib.startTUN(rawFd, MTU)
        tunActive.set(true)

        Log.i(TAG, "VPN tunnel established (fd=$rawFd, mtu=$MTU)")
    }

    private fun stop() {
        if (tunActive.compareAndSet(true, false)) {
            runCatching { Golib.stopTUN() }
        }
        runCatching { Golib.stopClient() }
        Golib.setFdProtector(null)
        stopSelf()
    }

    override fun onRevoke() {
        stop()
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BedlamVpn"
        private const val MTU = 1500
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
        const val EXTRA_CONFIG_JSON = "config_json"
    }
}
