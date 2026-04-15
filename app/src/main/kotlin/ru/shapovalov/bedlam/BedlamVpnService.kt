package ru.shapovalov.bedlam

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import golib.FdProtector
import golib.Golib

@SuppressLint("VpnServicePolicy")
class BedlamVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        try {
            registerProtector()
            createTunnel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stop()
        }

        return START_STICKY
    }

    private fun registerProtector() {
        Golib.setFdProtector { fd -> this@BedlamVpnService.protect(fd) }
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

        val fd = builder.establish() ?: throw IllegalStateException("VpnService.establish() returned null")
        tunFd = fd

        Golib.startTUN(fd.fd, MTU)

        Log.i(TAG, "VPN tunnel established (fd=${fd.fd}, mtu=$MTU)")
    }

    private fun stop() {
        runCatching { Golib.stopTUN() }
        runCatching { tunFd?.close() }
        tunFd = null
        Golib.setFdProtector(null)
        stopSelf()
    }

    override fun onRevoke() {
        stop()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BedlamVpn"
        private const val MTU = 1500
        const val ACTION_STOP = "ru.shapovalov.bedlam.STOP_VPN"
    }
}
