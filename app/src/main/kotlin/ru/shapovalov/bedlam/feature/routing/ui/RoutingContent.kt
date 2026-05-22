package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.routing.data.RoutePresets
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponent
import ru.shapovalov.bedlam.ui.theme.spacing
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingContent(component: RoutingComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing
    var showAddSource by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routing_title)) },
                navigationIcon = {
                    IconButton(onClick = component::onBackPressed) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = component::onRefreshAll,
                        enabled = !state.isRefreshing && state.config.sources.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.routing_refresh_cd),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.large, vertical = spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            BasicsCard(
                bypassLan = state.config.bypassLan,
                ipv6Mode = state.config.ipv6Mode,
                dnsMode = state.config.dnsMode,
                customDns = state.config.customDns,
                onSetBypassLan = component::onSetBypassLan,
                onSetIpv6Mode = component::onSetIpv6Mode,
                onSetDnsMode = component::onSetDnsMode,
                onSetCustomDns = component::onSetCustomDns,
            )

            SourcesCard(
                sources = state.config.sources,
                isRefreshing = state.isRefreshing,
                onAdd = { showAddSource = true },
                onPresets = { showPresets = true },
                onToggle = component::onSetSourceEnabled,
                onDelete = component::onRemoveSource,
            )

            Text(
                text = stringResource(R.string.routing_reconnect_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.small),
            )
            Spacer(Modifier.height(spacing.large))
        }
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onSave = { source ->
                component.onAddSource(source)
                showAddSource = false
            },
        )
    }

    if (showPresets) {
        PresetsDialog(
            onDismiss = { showPresets = false },
            onPick = {
                component.onAddPreset(it)
                showPresets = false
            },
        )
    }
}

@Composable
private fun BasicsCard(
    bypassLan: Boolean,
    ipv6Mode: Ipv6Mode,
    dnsMode: DnsMode,
    customDns: List<String>,
    onSetBypassLan: (Boolean) -> Unit,
    onSetIpv6Mode: (Ipv6Mode) -> Unit,
    onSetDnsMode: (DnsMode) -> Unit,
    onSetCustomDns: (List<String>) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            ToggleRow(
                title = stringResource(R.string.routing_bypass_lan_title),
                subtitle = stringResource(R.string.routing_bypass_lan_subtitle),
                checked = bypassLan,
                onCheckedChange = onSetBypassLan,
            )
            DividerRow()
            DropdownRow(
                title = stringResource(R.string.routing_ipv6_title),
                value = ipv6Mode.label(),
                options = Ipv6Mode.entries.map { it to it.label() },
                onPick = onSetIpv6Mode,
            )
            DividerRow()
            DropdownRow(
                title = stringResource(R.string.routing_dns_title),
                value = dnsMode.label(),
                subtitle = if (dnsMode == DnsMode.System) stringResource(R.string.routing_dns_system_warning) else null,
                options = DnsMode.entries.map { it to it.label() },
                onPick = onSetDnsMode,
            )
            if (dnsMode == DnsMode.Custom) {
                DividerRow()
                CustomDnsEditor(servers = customDns, onChange = onSetCustomDns)
            }
        }
    }
}

@Composable
private fun SourcesCard(
    sources: List<ResolvedSource>,
    isRefreshing: Boolean,
    onAdd: () -> Unit,
    onPresets: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.large, vertical = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.routing_sources_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(spacing.xSmall))
                    Text(
                        stringResource(R.string.routing_sources_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.routing_sources_add_cd))
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.xSmall),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                AssistChip(onClick = onPresets, label = { Text(stringResource(R.string.routing_presets_chip)) })
            }

            if (sources.isEmpty()) {
                Text(
                    stringResource(R.string.routing_sources_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.large),
                )
            } else {
                sources.forEachIndexed { idx, resolved ->
                    SourceRow(
                        resolved = resolved,
                        isRefreshing = isRefreshing,
                        onToggle = { onToggle(resolved.source.id, it) },
                        onDelete = { onDelete(resolved.source.id) },
                    )
                    if (idx != sources.lastIndex) DividerRow()
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    resolved: ResolvedSource,
    isRefreshing: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = resolved.source.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindChip(resolved.source)
                Spacer(Modifier.width(spacing.small))
                Text(
                    resolved.source.label(),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (resolved.source.comment.isNotBlank()) {
                Spacer(Modifier.height(spacing.xSmall))
                Text(
                    resolved.source.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                resolutionSummary(resolved, isRefreshing),
                style = MaterialTheme.typography.labelSmall,
                color = if (resolved.lastError != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.routing_sources_delete_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KindChip(source: DirectRouteSource) {
    val (label, color) = when (source) {
        is DirectRouteSource.Cidr -> "CIDR" to MaterialTheme.colorScheme.primary
        is DirectRouteSource.Asn -> "ASN" to MaterialTheme.colorScheme.tertiary
        is DirectRouteSource.Domain -> "DNS" to MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
        )
    }
}

@Composable
private fun resolutionSummary(resolved: ResolvedSource, isRefreshing: Boolean): String {
    val count = resolved.cidrs.size
    if (isRefreshing && resolved.source !is DirectRouteSource.Cidr) {
        return stringResource(R.string.routing_source_resolving)
    }
    resolved.lastError?.let { return stringResource(R.string.routing_source_error, it) }
    if (count == 0 && resolved.source !is DirectRouteSource.Cidr) {
        return stringResource(R.string.routing_source_pending)
    }
    val updated = resolved.lastResolvedMillis?.let { formatRelative(it) }
    return if (updated != null) stringResource(R.string.routing_source_count_with_time, count, updated)
    else stringResource(R.string.routing_source_count, count)
}

private fun formatRelative(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / 60 / 24} d ago"
    }
}

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
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(spacing.xSmall))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(spacing.medium))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> DropdownRow(
    title: String,
    value: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    subtitle: String? = null,
) {
    val spacing = MaterialTheme.spacing
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = spacing.large, vertical = spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Spacer(Modifier.height(spacing.xSmall))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onPick(key); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CustomDnsEditor(servers: List<String>, onChange: (List<String>) -> Unit) {
    val spacing = MaterialTheme.spacing
    var text by remember(servers) { mutableStateOf(servers.joinToString(", ")) }
    Column(modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.small)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it.split(',', '\n').map(String::trim).filter(String::isNotEmpty))
            },
            label = { Text(stringResource(R.string.routing_dns_custom_label)) },
            placeholder = { Text(stringResource(R.string.routing_dns_custom_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
    }
}

private enum class SourceKind { CIDR, ASN, DOMAIN }

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onSave: (DirectRouteSource) -> Unit,
) {
    var kind by remember { mutableStateOf(SourceKind.CIDR) }
    var value by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val parsed: DirectRouteSource? = remember(kind, value, comment) {
        val v = value.trim()
        if (v.isEmpty()) null else when (kind) {
            SourceKind.CIDR -> Cidr.parseOrNull(v)?.let {
                DirectRouteSource.Cidr(UUID.randomUUID().toString(), it, comment, true, 0)
            }
            SourceKind.ASN -> v.removePrefix("AS").removePrefix("as").toIntOrNull()?.let {
                DirectRouteSource.Asn(UUID.randomUUID().toString(), it, comment, true, 0)
            }
            SourceKind.DOMAIN -> if (v.contains('.') && !v.contains(' ')) {
                DirectRouteSource.Domain(UUID.randomUUID().toString(), v, comment, true, 0)
            } else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_sources_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                    SourceKind.entries.forEach { entry ->
                        FilterChip(
                            selected = kind == entry,
                            onClick = { kind = entry },
                            label = { Text(entry.label()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.routing_sources_add_value_label)) },
                    placeholder = { Text(kind.placeholder()) },
                    singleLine = true,
                    isError = value.isNotEmpty() && parsed == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.routing_sources_add_comment_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onSave) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PresetsDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_presets_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                RoutePresets.ALL.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(preset.id) }
                            .padding(MaterialTheme.spacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.medium))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
                            Text(
                                preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
                            Text(
                                stringResource(R.string.routing_preset_asn_count, preset.asns.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

@Composable
private fun DividerRow() {
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
private fun SourceKind.label(): String = stringResource(
    when (this) {
        SourceKind.CIDR -> R.string.routing_kind_cidr
        SourceKind.ASN -> R.string.routing_kind_asn
        SourceKind.DOMAIN -> R.string.routing_kind_domain
    }
)

@Composable
private fun SourceKind.placeholder(): String = when (this) {
    SourceKind.CIDR -> "10.0.0.0/8"
    SourceKind.ASN -> "AS13238"
    SourceKind.DOMAIN -> "yandex.ru"
}
