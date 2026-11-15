package ru.shapovalov.bedlam

import android.content.Intent
import android.net.VpnService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.latency.PingProfileUseCase
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DiagnosticResult
import ru.shapovalov.hysteria.api.DirectDnsConfig
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.config.BehaviorOptions
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import java.net.InetSocketAddress
import java.net.Socket
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Requires go run ./cmd/dnsfixture -public-https and Internet on the host. */
@RunWith(AndroidJUnit4::class)
class LatencyVpnTest {
    @Test
    fun authenticatedLatencyDisplaysAndActiveProbeReusesTunnel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = context.appComponent
        val profiles = component.profileRepository
        val previousId = profiles.getActiveId()
        val routing = component.routingRepository
        val previousRouting = routing.get()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }
        assertNull(VpnService.prepare(context))
        val profile = Profile(
            "latency-integration", "Latency integration",
            HysteriaConfig(
                server = ServerCredentials("10.0.2.2:18443", "bedlam-1.7.0-fixture"),
                tls = TlsOptions(tlsInsecure = true),
            ), 0, 0,
        )
        profiles.upsert(profile)
        profiles.setActiveId(profile.id)
        var activity: ActivityScenario<MainActivity>? = null
        try {
            routing.setBypassLan(enabled = true)
            routing.setIpv6Mode(Ipv6Mode.Disabled)
            activity = ActivityScenario.launch(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            val ping = PingProfileUseCase(context, component.hysteriaClient, routing)
            val cold = withTimeout(20.seconds) { ping(profile) }
            assertTrue("An inactive profile must test authenticated HTTPS: $cold", cold is LatencyResult.Success)
            val wrongPassword = profile.copy(config = profile.config.copy(
                server = profile.config.server.copy(auth = "wrong-password"),
            ))
            assertEquals(LatencyResult.ConnectionFailed, withTimeout(20.seconds) { ping(wrongPassword) })
            assertTrue(component.hysteriaClient.state.value is ConnectionState.Disconnected)

            component.vpnServiceLauncher.start(profile, userInitiated = true)
            withTimeout(15.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Connected } }
            assertTrue(
                "Connected profile must automatically display real milliseconds",
                device.wait(Until.hasObject(By.text(Pattern.compile("[0-9]+ ms"))), 20_000),
            )
            val before = counter("auth|accepted")
            val requestCount = counter("tcp|cp.cloudflare.com:443")
            val result = component.hysteriaClient.measureLatency(
                profile.config, DirectDnsConfig(),
                object : HysteriaClient.SocketProtector {
                    override fun protect(fd: Int): Boolean = error("Active probe must reuse its session")
                    override fun bindAndProtect(fd: Int): Boolean = error("Active probe must reuse its session")
                },
            )
            assertTrue("Active HTTPS probe: $result", result is DiagnosticResult.Ok)
            assertEquals("Active probe opened a second Hysteria session", before, counter("auth|accepted"))
            assertTrue("HTTPS did not reach Hysteria server", counter("tcp|cp.cloudflare.com:443") > requestCount)
            assertTrue(component.hysteriaClient.state.value is ConnectionState.Connected)
            assertTrue(http("/").contains("bedlam-fixture-ok"))

            val connected = component.hysteriaClient.state.value as ConnectionState.Connected
            val requestsBeforeReconnect = counter("tcp|cp.cloudflare.com:443")
            component.hysteriaClient.resetConnections()
            withTimeout(20.seconds) {
                component.hysteriaClient.state.first {
                    it is ConnectionState.Connected &&
                        it.info.connectionSequence > connected.info.connectionSequence
                }
                while (counter("tcp|cp.cloudflare.com:443") <= requestsBeforeReconnect) delay(100.milliseconds)
            }
            assertTrue(
                "Reconnect must replace the cleared latency with a fresh result",
                device.wait(Until.hasObject(By.text(Pattern.compile("[0-9]+ ms"))), 20_000),
            )

            device.setOrientationLeft()
            device.waitForIdle()
            assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("[0-9]+ ms"))), 5_000))
            device.setOrientationNatural()
            component.vpnServiceLauncher.stop()
            withTimeout(10.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Disconnected } }
            assertTrue("Disconnect left a stale latency", device.wait(Until.gone(By.text(Pattern.compile("[0-9]+ ms"))), 5_000))

            val withoutFastOpen = profile.copy(config = profile.config.copy(
                behavior = BehaviorOptions(fastOpen = false),
            ))
            profiles.upsert(withoutFastOpen)
            component.vpnServiceLauncher.start(withoutFastOpen, userInitiated = true)
            val slowOpenConnection = withTimeout(15.seconds) {
                component.hysteriaClient.state.first { it is ConnectionState.Connected }
            } as ConnectionState.Connected
            assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("[0-9]+ ms"))), 20_000))
            val authBeforeFallback = counter("auth|accepted")
            val fallback = withTimeout(20.seconds) { ping(withoutFastOpen) }
            assertTrue("Fast Open disabled must use a working fallback: $fallback", fallback is LatencyResult.Success)
            assertTrue("Fallback must own a separate session", counter("auth|accepted") > authBeforeFallback)
            val afterFallback = component.hysteriaClient.state.value as ConnectionState.Connected
            assertEquals(slowOpenConnection.info.connectionSequence, afterFallback.info.connectionSequence)
        } finally {
            component.vpnServiceLauncher.stop()
            withTimeout(10.seconds) { component.hysteriaClient.state.first { it is ConnectionState.Disconnected } }
            delay(300.milliseconds)
            activity?.close()
            device.setOrientationNatural()
            device.unfreezeRotation()
            profiles.setActiveId(previousId)
            profiles.delete(profile.id)
            routing.setBypassLan(previousRouting.bypassLan)
            routing.setIpv6Mode(previousRouting.ipv6Mode)
        }
    }

    private fun counter(key: String) =
        org.json.JSONObject(http("/stats").substringAfter("\r\n\r\n")).optInt(key, 0)

    private fun http(path: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("10.0.2.2", 18080), 5_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: fixture\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }
}
