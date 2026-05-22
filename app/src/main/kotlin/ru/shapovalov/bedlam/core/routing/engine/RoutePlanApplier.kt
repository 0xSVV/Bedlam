package ru.shapovalov.bedlam.core.routing.engine

import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.util.Log
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePlan
import java.net.InetAddress

/** Applies a [RoutePlan] to a `VpnService.Builder`. */
@Inject
class RoutePlanApplier {

    fun apply(plan: RoutePlan, builder: VpnService.Builder) {
        plan.claimedV4.forEach { builder.addRoute(it.toInetAddress(), it.prefixLength) }
        plan.claimedV6.forEach { builder.addRoute(it.toInetAddress(), it.prefixLength) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            plan.excludedV4.forEach { builder.excludeRoute(it.toIpPrefix()) }
            plan.excludedV6.forEach { builder.excludeRoute(it.toIpPrefix()) }
        }

        plan.dnsServers.forEach { builder.addDnsServer(it) }

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

    private fun Cidr.toInetAddress(): InetAddress = InetAddress.getByAddress(networkBytes)

    private fun Cidr.toIpPrefix(): IpPrefix = IpPrefix(toInetAddress(), prefixLength)

    companion object { private const val TAG = "RoutePlanApplier" }
}
