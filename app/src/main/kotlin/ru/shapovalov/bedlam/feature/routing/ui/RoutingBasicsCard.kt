package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.DnsServer
import ru.shapovalov.bedlam.core.routing.domain.model.DnsServerParse
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.api.DnsTransport

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BasicsCard(
    modifier: Modifier = Modifier,
    bypassLan: Boolean,
    ipv6Mode: Ipv6Mode,
    dnsMode: DnsMode,
    dnsTransport: DnsTransport,
    customDns: List<String>,
    onSetBypassLan: (Boolean) -> Unit,
    onSetIpv6Mode: (Ipv6Mode) -> Unit,
    onSetDnsMode: (DnsMode) -> Unit,
    onSetDnsTransport: (DnsTransport) -> Unit,
    onSetCustomDns: (List<String>) -> Unit,
) {
    val ipv6Options = remember { Ipv6Mode.entries.toList() }
    val dnsOptions = remember { DnsMode.entries.toList() }
    val transportOptions = remember(dnsMode) { DnsPresets.supportedTransports(dnsMode) }
    val effectiveTransport = DnsPresets.effectiveTransport(dnsMode, dnsTransport)

    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(vertical = MaterialTheme.spacing.small)) {
            ToggleRow(
                title = stringResource(R.string.routing_bypass_lan_title),
                subtitle = stringResource(R.string.routing_bypass_lan_subtitle),
                checked = bypassLan,
                onCheckedChange = onSetBypassLan,
            )
            DividerRow()
            ToggleGroupRow(
                title = stringResource(R.string.routing_ipv6_title),
                selected = ipv6Mode,
                options = ipv6Options,
                onPick = onSetIpv6Mode,
            ) { mode ->
                Text(
                    text = mode.label(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DividerRow()
            DropdownRow(
                title = stringResource(R.string.routing_dns_title),
                value = dnsMode.label(),
                subtitle = if (dnsMode == DnsMode.System) stringResource(R.string.routing_dns_system_warning) else null,
                options = dnsOptions,
                renderLabel = { it.label() },
                onPick = onSetDnsMode,
            )
            DividerRow()
            DropdownRow(
                title = stringResource(R.string.routing_dns_transport_title),
                value = effectiveTransport.label(),
                subtitle = if (dnsMode == DnsMode.System) {
                    stringResource(R.string.routing_dns_transport_system_hint)
                } else {
                    null
                },
                options = transportOptions,
                renderLabel = { it.label() },
                onPick = onSetDnsTransport,
            )
            if (dnsMode == DnsMode.Custom) {
                DividerRow()
                CustomDnsEditor(
                    initial = customDns,
                    transport = effectiveTransport,
                    onChange = onSetCustomDns,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ToggleGroupRow(
    title: String,
    selected: T,
    options: List<T>,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEachIndexed { index, option ->
                ToggleButton(
                    checked = selected == option,
                    onCheckedChange = { if (it) onPick(option) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) {
                    content(option)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMediumEmphasized)
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(spacing.medium))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> DropdownRow(
    title: String,
    value: String,
    options: List<T>,
    renderLabel: @Composable (T) -> String,
    onPick: (T) -> Unit,
    subtitle: String? = null,
) {
    val spacing = MaterialTheme.spacing
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMediumEmphasized)
            if (subtitle != null) {
                Spacer(Modifier.height(spacing.xSmall))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        Box {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(renderLabel(option)) },
                        onClick = { onPick(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomDnsEditor(
    initial: List<String>,
    transport: DnsTransport,
    onChange: (List<String>) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var text by remember { mutableStateOf(initial.joinToString(", ")) }
    val entries = remember(text) { text.split(',', '\n').map(String::trim).filter(String::isNotEmpty) }
    val invalid = remember(entries, transport) {
        entries.filter { DnsServer.parse(it, transport) is DnsServerParse.Invalid }
    }
    Column(modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.small)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it.split(',', '\n').map(String::trim).filter(String::isNotEmpty))
            },
            label = { Text(stringResource(R.string.routing_dns_custom_label)) },
            placeholder = { Text(transport.placeholder()) },
            supportingText = {
                Text(
                    if (invalid.isEmpty()) {
                        transport.hint()
                    } else {
                        stringResource(R.string.routing_dns_custom_invalid, invalid.joinToString(", "))
                    }
                )
            },
            isError = invalid.isNotEmpty(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
    }
}

@Composable
internal fun DividerRow() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun Ipv6Mode.label(): String = stringResource(
    when (this) {
        Ipv6Mode.Enabled -> R.string.routing_ipv6_enabled
        Ipv6Mode.Disabled -> R.string.routing_ipv6_disabled
        Ipv6Mode.BypassOnly -> R.string.routing_ipv6_bypass_only
    }
)

@Composable
private fun DnsMode.label(): String = stringResource(
    when (this) {
        DnsMode.System -> R.string.routing_dns_system
        DnsMode.Cloudflare -> R.string.routing_dns_cloudflare
        DnsMode.Google -> R.string.routing_dns_google
        DnsMode.Custom -> R.string.routing_dns_custom
    }
)

@Composable
private fun DnsTransport.label(): String = stringResource(
    when (this) {
        DnsTransport.Udp -> R.string.routing_dns_transport_udp
        DnsTransport.Tcp -> R.string.routing_dns_transport_tcp
        DnsTransport.Tls -> R.string.routing_dns_transport_tls
        DnsTransport.Https -> R.string.routing_dns_transport_https
    }
)

@Composable
private fun DnsTransport.placeholder(): String = stringResource(
    when (this) {
        DnsTransport.Udp, DnsTransport.Tcp -> R.string.routing_dns_custom_placeholder_ip
        DnsTransport.Tls -> R.string.routing_dns_custom_placeholder_tls
        DnsTransport.Https -> R.string.routing_dns_custom_placeholder_https
    }
)

@Composable
private fun DnsTransport.hint(): String = stringResource(
    when (this) {
        DnsTransport.Udp, DnsTransport.Tcp -> R.string.routing_dns_custom_hint_ip
        DnsTransport.Tls -> R.string.routing_dns_custom_hint_tls
        DnsTransport.Https -> R.string.routing_dns_custom_hint_https
    }
)
