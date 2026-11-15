package ru.shapovalov.bedlam.core.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.util.Log
import ru.shapovalov.hysteria.api.HysteriaClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android's local HTTP proxy accepts callers across app/user boundaries.
 * Its outgoing socket belongs to the proxy, hiding the original caller from
 * per-app VPN routing. Reject that socket inside the TUN instead of excluding
 * the proxy package, which would send its traffic directly.
 */
internal class VpnTcpFlowFilter(context: Context) : HysteriaClient.TcpFlowFilter {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val proxyUid = proxyUid(context.packageManager)
    private val reportedBlocked = AtomicBoolean()
    private val reportedUnknown = AtomicBoolean()

    override fun allow(
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): Boolean {
        val uid = try {
            connectivity.getConnectionOwnerUid(
                android.system.OsConstants.IPPROTO_TCP,
                InetSocketAddress(InetAddresses.parseNumericAddress(sourceAddress), sourcePort),
                InetSocketAddress(InetAddresses.parseNumericAddress(destinationAddress), destinationPort),
            )
        } catch (_: Exception) {
            android.os.Process.INVALID_UID
        }
        if (!isAllowedOwner(uid, proxyUid)) {
            val report = if (uid < 0) reportedUnknown else reportedBlocked
            if (report.compareAndSet(false, true)) {
                Log.w(
                    "VpnTcpFlowFilter",
                    if (uid < 0) {
                        "Blocked TCP flow whose owner could not be verified"
                    } else {
                        "Blocked Android system proxy forwarding into the VPN"
                    },
                )
            }
            return false
        }
        return true
    }

    companion object {
        internal fun isAllowedOwner(uid: Int, proxyUid: Int?): Boolean = (uid >= 0) && (uid != proxyUid)

        @Suppress("DEPRECATION")
        private fun proxyUid(manager: PackageManager): Int? = try {
            // The system package UID is stable for the lifetime of this VPN.
            manager.getApplicationInfo("com.android.proxyhandler", 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
