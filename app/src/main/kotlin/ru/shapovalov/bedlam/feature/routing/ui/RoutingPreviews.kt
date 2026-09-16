package ru.shapovalov.bedlam.feature.routing.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.hysteria.api.DnsTransport

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class RoutingPreviews

private const val HourMillis = 3_600_000L

private val asnSource = DirectRouteSource.Asn(
    id = "asn",
    asn = 64500,
    comment = "Example Networks",
    enabled = true,
    orderIndex = 0,
)

private val domainSource = DirectRouteSource.Domain(
    id = "domain",
    hostname = "intranet.example.com",
    comment = "",
    enabled = true,
    orderIndex = 1,
)

private val cidrSource = DirectRouteSource.Cidr(
    id = "cidr",
    cidr = Cidr.parse("192.0.2.0/24"),
    comment = "Printer subnet",
    enabled = false,
    orderIndex = 2,
)

private fun ipv4Networks(count: Int): List<Cidr> =
    List(count) { index -> Cidr.parse("10.${index / 256}.${index % 256}.0/24") }

@Composable
private fun RoutingPreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun BasicsCardPreview(
    bypassLan: Boolean,
    ipv6Mode: Ipv6Mode,
    dnsMode: DnsMode,
    dnsTransport: DnsTransport,
    customDns: List<String>,
    mtu: Int,
) {
    RoutingPreview {
        BasicsCard(
            bypassLan = bypassLan,
            ipv6Mode = ipv6Mode,
            dnsMode = dnsMode,
            dnsTransport = dnsTransport,
            customDns = customDns,
            mtu = mtu,
            onSetBypassLan = {},
            onSetIpv6Mode = {},
            onSetDnsMode = {},
            onSetDnsTransport = {},
            onSetCustomDns = {},
            onSetMtu = {},
        )
    }
}

@RoutingPreviews
@Composable
private fun BasicsCardDefaultsPreview() {
    val defaults = RoutingConfig()
    BasicsCardPreview(
        bypassLan = defaults.bypassLan,
        ipv6Mode = defaults.ipv6Mode,
        dnsMode = defaults.dnsMode,
        dnsTransport = defaults.dnsTransport,
        customDns = defaults.customDns,
        mtu = defaults.mtu,
    )
}

@RoutingPreviews
@Composable
private fun BasicsCardCustomDnsPreview() {
    BasicsCardPreview(
        bypassLan = false,
        ipv6Mode = Ipv6Mode.BypassOnly,
        dnsMode = DnsMode.Custom,
        dnsTransport = DnsTransport.Https,
        customDns = listOf("https://dns.example/dns-query"),
        mtu = 1400,
    )
}

@RoutingPreviews
@Composable
private fun SourcesHeaderCardPreview() {
    RoutingPreview { SourcesHeaderCard(onAdd = {}, onPresets = {}) }
}

@RoutingPreviews
@Composable
private fun EmptySourcesRowPreview() {
    RoutingPreview { EmptySourcesRow() }
}

@Composable
private fun SourceCardPreview(resolved: ResolvedSource, isRefreshing: Boolean = false) {
    RoutingPreview {
        SwipeableSourceCard(
            resolved = resolved,
            isRefreshing = isRefreshing,
            onToggle = { _, _ -> },
            onDelete = {},
        )
    }
}

@RoutingPreviews
@Composable
private fun SourceCardResolvedPreview() {
    SourceCardPreview(
        resolved = ResolvedSource(
            source = asnSource,
            cidrs = ipv4Networks(12),
            lastResolvedMillis = System.currentTimeMillis() - 2 * HourMillis,
            lastError = null,
        ),
    )
}

@RoutingPreviews
@Composable
private fun SourceCardFirstResolvePreview() {
    SourceCardPreview(
        resolved = ResolvedSource(
            source = domainSource,
            cidrs = emptyList(),
            lastResolvedMillis = null,
            lastError = null,
        ),
        isRefreshing = true,
    )
}

@RoutingPreviews
@Composable
private fun SourceCardFailedPreview() {
    SourceCardPreview(
        resolved = ResolvedSource(
            source = domainSource,
            cidrs = emptyList(),
            lastResolvedMillis = System.currentTimeMillis() - 30 * 24 * HourMillis,
            lastError = "no such host",
        ),
    )
}

@RoutingPreviews
@Composable
private fun SourceCardDisabledPreview() {
    SourceCardPreview(
        resolved = ResolvedSource(
            source = cidrSource,
            cidrs = listOf(cidrSource.cidr),
            lastResolvedMillis = null,
            lastError = null,
        ),
    )
}

@RoutingPreviews
@Composable
private fun SourceDetailsExpandedPreview() {
    RoutingPreview {
        SourceRowContent(
            resolved = ResolvedSource(
                source = asnSource,
                cidrs = ipv4Networks(201),
                lastResolvedMillis = System.currentTimeMillis() - 5 * 60_000L,
                lastError = "partial response from the registry",
            ),
            isRefreshing = false,
            expanded = true,
            onToggleExpanded = {},
            onToggleEnabled = {},
            onDelete = {},
        )
    }
}
