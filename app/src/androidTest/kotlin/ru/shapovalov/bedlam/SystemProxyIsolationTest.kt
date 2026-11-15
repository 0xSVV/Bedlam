package ru.shapovalov.bedlam

import android.content.Intent
import android.net.VpnService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Requires the local dnsfixture and an emulator with Android's ProxyService. */
@RunWith(AndroidJUnit4::class)
class SystemProxyIsolationTest {
    @Test
    fun systemProxyCannotBorrowTunnelButOrdinaryTcpWorks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = context.appComponent
        val device = UiDevice.getInstance(instrumentation)
        val profiles = component.profileRepository
        val previousId = profiles.getActiveId()
        val routing = component.routingRepository
        val previousRouting = routing.get()
        val appFilter = component.appFilterRepository
        val previousFilter = appFilter.get()
        val profile = Profile(
            "proxy-isolation", "Proxy isolation",
            HysteriaConfig(
                server = ServerCredentials("10.0.2.2:18443", "bedlam-1.7.0-fixture"),
                tls = TlsOptions(tlsInsecure = true),
            ), 0, 0,
        )
        device.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }
        assertNull(VpnService.prepare(context))
        profiles.upsert(profile)
        profiles.setActiveId(profile.id)
        var activity: ActivityScenario<MainActivity>? = null
        try {
            component.vpnServiceLauncher.stop()
            withTimeout(10.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Disconnected } }
            routing.setBypassLan(enabled = true)
            routing.setIpv6Mode(Ipv6Mode.Enabled)
            activity = ActivityScenario.launch(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            device.executeShellCommand("am stopservice --user 0 -n $PROXY_SERVICE")
            delay(300.milliseconds)
            val beforePorts = listeningPorts(device)
            device.executeShellCommand("cmd deviceidle tempwhitelist -d 600000 $PROXY_PACKAGE")
            val start = device.executeShellCommand("am startservice --user 0 -n $PROXY_SERVICE")
            assertFalse("Could not start system proxy: $start", start.contains("Error"))
            val proxyPort = withTimeout(5.seconds) {
                var port: Int? = null
                while (port == null) {
                    port = (listeningPorts(device) - beforePorts).singleOrNull()
                    if (port == null) delay(100.milliseconds)
                }
                port
            }
            for (mode in AppFilterMode.entries) {
                appFilter.setPackages(
                    if (mode == AppFilterMode.Allowlist) setOf(context.packageName, PROXY_PACKAGE)
                    else setOf("ru.netfreedom.proxybypass"),
                )
                appFilter.setMode(mode)
                component.vpnServiceLauncher.start(profile, userInitiated = true)
                withTimeout(15.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Connected } }

                for (host in listOf("203.0.113.7", "2001:db8::7")) {
                    val authority = if (':' in host) "[$host]" else host
                    val direct = request(host, 80, "GET / HTTP/1.0\r\nHost: fixture\r\n\r\n")
                    assertTrue("Ordinary captured TCP failed in $mode to $host: $direct", direct.contains("bedlam-fixture-ok"))
                    val forwardedBefore = tcpCount("$authority:80")
                    val throughProxy = runCatching {
                        request("127.0.0.1", proxyPort, "GET http://$authority/ HTTP/1.0\r\nHost: $authority\r\n\r\n")
                    }.getOrDefault("")
                    assertFalse("System proxy borrowed the tunnel in $mode to $host", throughProxy.contains("bedlam-fixture-ok"))
                    assertEquals("Proxy request reached Hysteria in $mode to $host", forwardedBefore, tcpCount("$authority:80"))
                }
                assertTrue(component.hysteriaClient.state.value is ConnectionState.Connected)

                component.vpnServiceLauncher.stop()
                withTimeout(10.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Disconnected } }
                delay(300.milliseconds)
            }
        } finally {
            component.vpnServiceLauncher.stop()
            withTimeout(10.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Disconnected } }
            device.executeShellCommand("am stopservice --user 0 -n $PROXY_SERVICE")
            activity?.close()
            profiles.setActiveId(previousId)
            profiles.delete(profile.id)
            appFilter.setPackages(previousFilter.packages)
            appFilter.setMode(previousFilter.mode)
            routing.setBypassLan(previousRouting.bypassLan)
            routing.setIpv6Mode(previousRouting.ipv6Mode)
        }
    }

    private fun listeningPorts(device: UiDevice): Set<Int> =
        device.executeShellCommand("ss -ltn").lineSequence()
            .mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.firstOrNull() != "LISTEN") null
                else columns.getOrNull(3)?.substringAfterLast(':')?.toIntOrNull()
            }.toSet()

    private fun tcpCount(destination: String): Int {
        val response = request("10.0.2.2", 18080, "GET /stats HTTP/1.0\r\nHost: fixture\r\n\r\n")
        return org.json.JSONObject(response.substringAfter("\r\n\r\n")).optInt("tcp|$destination")
    }

    private fun request(host: String, port: Int, message: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 3_000)
        socket.soTimeout = 3_000
        socket.getOutputStream().write(message.toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }

    private companion object {
        const val PROXY_PACKAGE = "com.android.proxyhandler"
        const val PROXY_SERVICE = "$PROXY_PACKAGE/.ProxyService"
    }
}
