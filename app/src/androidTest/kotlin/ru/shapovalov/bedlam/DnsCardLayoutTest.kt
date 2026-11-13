package ru.shapovalov.bedlam

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.feature.routing.ui.Ipv6Card
import ru.shapovalov.bedlam.feature.routing.ui.RemoteDnsCard
import ru.shapovalov.hysteria.api.DnsTransport

@RunWith(AndroidJUnit4::class)
class DnsCardLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun remoteDnsTitleProviderAndTransportHaveEvenSpacing() {
        compose.setContent {
            MaterialTheme {
                RemoteDnsCard(
                    DnsMode.Cloudflare, DnsTransport.Tcp, emptyList(),
                    onSetDnsMode = {}, onSetDnsTransport = {}, onSetCustomDns = {},
                )
            }
        }
        val titleBottom = compose.onNodeWithText("Remote DNS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
        val providerBounds = compose.onNodeWithText("Provider", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val transportTop = compose.onNodeWithText("Transport", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        with(providerBounds) {
            assertEquals("Visible gaps should match", top - titleBottom, transportTop - bottom, 1f)
        }
    }

    @Test
    fun ipv6BypassExplainsDirectDnsVisibility() {
        compose.setContent {
            MaterialTheme { Ipv6Card(Ipv6Mode.BypassOnly, onSetIpv6Mode = {}) }
        }
        compose.onNodeWithText(
            "IPv6 lookups use Direct DNS outside the tunnel. Choose DNS over TLS or HTTPS there to hide these queries from your network.",
        ).assertExists()
    }
}
