package ru.shapovalov.bedlam

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.di.appComponent
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DnsTransport
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Requires the host fixture: cd hysteria/golib && go run ./cmd/dnsfixture. */
@RunWith(AndroidJUnit4::class)
class DirectDnsVpnTest {
    @Test
    fun tcpBootstrapConnectsAndRemoteDnsStaysInsideTunnel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = context.appComponent
        val repository = component.routingRepository
        val before = repository.get()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN allow")
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName, "android.permission.ACCESS_LOCAL_NETWORK",
            )
        }
        assertNull("VPN consent must be granted on the emulator", VpnService.prepare(context))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(http("10.0.2.2", "/").contains("bedlam-fixture-ok"))
        val profile = Profile(
            id = "direct-dns-integration",
            name = "Direct DNS integration",
            config = HysteriaConfig(
                server = ServerCredentials("fixture-server.test:18443", "bedlam-1.7.0-fixture"),
                tls = TlsOptions(tlsInsecure = true),
            ),
            createdAt = 0,
            updatedAt = 0,
        )
        val directName = "direct-${System.nanoTime()}.test"
        val directSourceId = "fixture-$directName"
        try {
            repository.setBypassLan(enabled = true)
            repository.setIpv6Mode(Ipv6Mode.Disabled)
            repository.setDnsCacheEnabled(enabled = false)
            repository.setDnsMode(DnsMode.Custom)
            repository.setDnsTransport(DnsTransport.Tcp)
            repository.setCustomDns(listOf("203.0.113.53:53"))
            repository.setDirectDnsMode(DnsMode.Custom)
            repository.setDirectDnsTransport(DnsTransport.Tcp)
            repository.setDirectCustomDns(listOf("10.0.2.2:15353"))
            component.vpnServiceLauncher.start(profile, userInitiated = true)
            val state = withTimeout(15.seconds) {
                component.hysteriaClient.state.first {
                    (it is ConnectionState.Connected) || (it is ConnectionState.Error)
                }
            }
            assertTrue("Client must connect using configured Direct TCP DNS; state=$state", state is ConnectionState.Connected)
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            withTimeout(10.seconds) {
                while (connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) != true
                ) delay(100.milliseconds)
            }
            val name = "remote-${System.nanoTime()}.test"
            val response = query(name)
            assertEquals("DNS response must succeed", 0, response[3].toInt() and 15)
            assertTrue("application TCP must cross the real Hysteria server", http("203.0.113.7", "/").contains("bedlam-fixture-ok"))
            val counters = http("10.0.2.2", "/stats")
            assertTrue(counters, counters.contains("direct-tcp|fixture-server.test"))
            assertTrue(counters, counters.contains("remote-tcp|$name"))
            assertTrue("Remote query must not use Direct DNS: $counters", !counters.contains("direct-tcp|$name"))

            repository.upsertSource(DirectRouteSource.Domain(directSourceId, directName, "", enabled = true, orderIndex = 0))
            repository.setIpv6Mode(Ipv6Mode.BypassOnly)
            awaitDirectAnswer(directName, "direct-tcp")
            val remoteBefore = count("remote-tcp", directName)
            assertEquals(0, query(directName)[3].toInt() and 15)
            assertEquals("Once applied, direct domains must stay outside the tunnel", remoteBefore, count("remote-tcp", directName))
            val ipv6Name = "ipv6-${System.nanoTime()}.test"
            assertEquals(0, query(ipv6Name, type = 28)[3].toInt() and 15)
            val directCounters = http("10.0.2.2", "/stats")
            assertTrue(directCounters, directCounters.contains("direct-tcp|$directName"))
            assertTrue(directCounters, directCounters.contains("direct-tcp|$ipv6Name"))
            assertTrue(directCounters, !directCounters.contains("remote-tcp|$ipv6Name"))

            repository.setDirectDns(DnsMode.Custom, DnsTransport.Udp, listOf("10.0.2.2:15353"), emptyMap())
            awaitDirectAnswer(directName, "direct-udp")
            assertEquals(0, query(directName)[3].toInt() and 15)
            val changedCounters = http("10.0.2.2", "/stats")
            assertTrue(
                "Changing Direct transport must recreate the resolver: $changedCounters",
                changedCounters.contains("direct-udp|$directName"),
            )
            // The direct-domain answer added 203.0.113.7 to direct routes. Use a different address.
            assertTrue(http("203.0.113.8", "/").contains("bedlam-fixture-ok"))
        } finally {
            component.vpnServiceLauncher.stop()
            withTimeout(10.seconds) {
                component.hysteriaClient.state.first { it is ConnectionState.Disconnected }
            }
            delay(300.milliseconds)
            repository.removeSource(directSourceId)
            with(before) {
                repository.setBypassLan(bypassLan)
                repository.setIpv6Mode(ipv6Mode)
                repository.setDnsCacheEnabled(dnsCacheEnabled)
                repository.setDnsMode(dnsMode)
                repository.setDnsTransport(dnsTransport)
                repository.setCustomDns(customDns)
                repository.setDirectDns(
                    directDnsMode, directDnsTransport, directCustomDns, directDnsBootstrap,
                )
            }
        }
    }

    private fun http(host: String, path: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress(host, 18080), 5_000)
        socket.soTimeout = 5_000
        socket.getOutputStream().write("GET $path HTTP/1.0\r\nHost: fixture\r\n\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }

    private fun count(transport: String, name: String): Int =
        org.json.JSONObject(http("10.0.2.2", "/stats").substringAfter("\r\n\r\n"))
            .optInt("$transport|$name", 0)

    private suspend fun awaitDirectAnswer(name: String, transport: String) = withTimeout(10.seconds) {
        while (true) {
            // Android can retain the VPN Network id when replacing its TUN.
            // Observe the actual server receiving traffic instead of that id.
            runCatching { query(name) }
            if (count(transport, name) > 0) break
            delay(100.milliseconds)
        }
    }

    private fun query(name: String, type: Int = 1): ByteArray {
        val bytes = mutableListOf<Byte>(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)
        name.split('.').forEach { label ->
            bytes += label.length.toByte()
            bytes += label.toByteArray().toList()
        }
        bytes += listOf(0, 0, type.toByte(), 0, 1)
        return DatagramSocket().use { socket ->
            socket.soTimeout = 5_000
            val request = bytes.toByteArray()
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("172.19.0.2"), 53))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            response.data.copyOf(response.length)
        }
    }
}
