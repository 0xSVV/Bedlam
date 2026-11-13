package ru.shapovalov.bedlam

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.feature.routing.ui.DirectDnsCard
import ru.shapovalov.hysteria.api.DnsTransport

@RunWith(AndroidJUnit4::class)
class DirectDnsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun customDnsIsAppliedOnlyWhenValidAndExplicitlySaved() {
        var saved: RoutingConfig? = null
        compose.setContent {
            MaterialTheme {
                DirectDnsCard(
                    config = RoutingConfig(),
                    onSave = { mode, transport, servers, bootstrap ->
                        saved = RoutingConfig(
                            directDnsMode = mode, directDnsTransport = transport,
                            directCustomDns = servers, directDnsBootstrap = bootstrap,
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("Provider").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertNull(saved)
        compose.onNodeWithText("Transport").performClick()
        compose.onNodeWithText("DNS over HTTPS").performClick()
        compose.onNodeWithText("DNS servers").performTextInput("https://dns.fixture.test/dns-query")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Bootstrap IP addresses").performTextInput("dns.fixture.test=10.0.2.2")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(DnsMode.Custom, saved?.directDnsMode)
            assertEquals(DnsTransport.Https, saved?.directDnsTransport)
            assertEquals(mapOf("dns.fixture.test" to listOf("10.0.2.2")), saved?.directDnsBootstrap)
        }
    }

    @Test
    fun presetsAdaptTransportAndSaveWithoutCustomBootstrapInput() {
        var savedMode: DnsMode? = null
        var savedTransport: DnsTransport? = null
        compose.setContent {
            MaterialTheme {
                DirectDnsCard(
                    config = RoutingConfig(directDnsMode = DnsMode.Cloudflare, directDnsTransport = DnsTransport.Tls),
                    onSave = { mode, transport, _, _ -> savedMode = mode; savedTransport = transport },
                )
            }
        }
        compose.onNodeWithText("Provider").performClick()
        compose.onNodeWithText("OpenDNS").performClick()
        compose.onNodeWithText("DNS over HTTPS").assertExists()
        compose.onNodeWithText("Bootstrap IP addresses").assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(DnsMode.OpenDns, savedMode)
            assertEquals(DnsTransport.Https, savedTransport)
        }
        compose.onNodeWithText("Provider").performClick()
        compose.onNodeWithText("AdGuard (ad and tracker blocking)").performClick()
        compose.onNodeWithText("Transport").performClick()
        compose.onNodeWithText("DNS over TLS").performClick()
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(DnsMode.AdGuard, savedMode)
            assertEquals(DnsTransport.Tls, savedTransport)
        }
    }

    @Test
    fun switchingToPlainDnsIgnoresHiddenInvalidBootstrap() {
        var saved: DnsTransport? = null
        compose.setContent {
            MaterialTheme {
                DirectDnsCard(
                    config = RoutingConfig(
                        directDnsMode = DnsMode.Custom,
                        directDnsTransport = DnsTransport.Https,
                        directCustomDns = listOf("https://dns.example/dns-query"),
                    ),
                    onSave = { _, transport, _, _ -> saved = transport },
                )
            }
        }
        compose.onNodeWithText("Bootstrap IP addresses").performTextInput("unfinished bootstrap")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Transport").performClick()
        compose.onNodeWithText("UDP").performClick()
        compose.onNodeWithText("Bootstrap IP addresses").assertDoesNotExist()
        compose.onNodeWithText("DNS servers").performTextReplacement("9.9.9.9")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(DnsTransport.Udp, saved) }
    }

    @Test
    fun directTransportMenuExcludesQuicTransports() {
        compose.setContent {
            MaterialTheme {
                DirectDnsCard(
                    config = RoutingConfig(directDnsMode = DnsMode.Cloudflare),
                    onSave = { _, _, _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Transport").performClick()
        compose.onNodeWithText("DNS over TLS").assertExists()
        compose.onNodeWithText("DNS over HTTPS").assertExists()
        compose.onNodeWithText("DNS over QUIC").assertDoesNotExist()
        compose.onNodeWithText("DNS over HTTP/3").assertDoesNotExist()
    }
}
