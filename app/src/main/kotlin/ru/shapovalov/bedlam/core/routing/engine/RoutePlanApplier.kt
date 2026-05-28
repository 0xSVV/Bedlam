package ru.shapovalov.bedlam.core.routing.engine

import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import java.net.InetAddress

/** Applies a [RoutePlan] to a `VpnService.Builder`. */
@Inject
class RoutePlanApplier {

    fun apply(plan: RoutePlan, builder: VpnService.Builder) {
        plan.claimedV4.forEach { addRouteSafely(builder, it) }
        plan.claimedV6.forEach { addRouteSafely(builder, it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            plan.excludedV4.forEach { excludeRouteSafely(builder, it) }
            plan.excludedV6.forEach { excludeRouteSafely(builder, it) }
        }

        plan.dnsServers.forEach { addDnsServerSafely(builder, it) }

        when (plan.appFilter.mode) {
            AppFilterMode.All -> Unit
            AppFilterMode.Allowlist -> plan.appFilter.packages.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { e ->
                        if (e is PackageManager.NameNotFoundException) {
                            Log.w(TAG, "Allowlist package not installed: $pkg")
                        } else throw e
                    }
            }

            AppFilterMode.Blocklist -> plan.appFilter.packages.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { e ->
                        if (e is PackageManager.NameNotFoundException) {
                            Log.w(TAG, "Blocklist package not installed: $pkg")
                        } else throw e
                    }
            }
        }
    }

    private fun addRouteSafely(builder: VpnService.Builder, cidr: Cidr) {
        try {
            builder.addRoute(cidr.toInetAddress(), cidr.prefixLength)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "addRoute rejected ${cidr.asString()}: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun excludeRouteSafely(builder: VpnService.Builder, cidr: Cidr) {
        try {
            builder.excludeRoute(cidr.toIpPrefix())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "excludeRoute rejected ${cidr.asString()}: ${e.message}")
        }
    }

    private fun addDnsServerSafely(builder: VpnService.Builder, address: String) {
        try {
            builder.addDnsServer(address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "addDnsServer rejected $address: ${e.message}")
        }
    }

    private fun Cidr.toInetAddress(): InetAddress = InetAddress.getByAddress(networkBytes)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Cidr.toIpPrefix(): IpPrefix = IpPrefix(toInetAddress(), prefixLength)

    companion object {
        private const val TAG = "RoutePlanApplier"
    }
}
