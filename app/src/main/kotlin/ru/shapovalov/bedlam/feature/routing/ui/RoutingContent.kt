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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpUpdateState
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteRule
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponent
import ru.shapovalov.bedlam.ui.theme.spacing
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingContent(component: RoutingComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing
    var editingRule by remember { mutableStateOf<DirectRouteRule?>(null) }
    var creatingRule by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }

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
            DirectRoutesCard(
                rules = state.config.directRoutes,
                onToggle = component::onSetDirectRouteEnabled,
                onEdit = { editingRule = it },
                onAdd = { creatingRule = true },
                onDelete = component::onRemoveDirectRoute,
            )
            GeoBypassCard(
                info = state.geoIpInfo,
                updateState = state.geoIpUpdateState,
                selected = state.config.geoDirectCountries,
                onDownload = component::onDownloadGeoIp,
                onRemove = component::onRemoveGeoIp,
                onOpenPicker = { showCountryPicker = true },
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

    if (editingRule != null || creatingRule) {
        DirectRouteEditorDialog(
            existing = editingRule,
            onDismiss = { editingRule = null; creatingRule = false },
            onSave = {
                component.onUpsertDirectRoute(it)
                editingRule = null
                creatingRule = false
            },
        )
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            available = state.availableCountries,
            selected = state.config.geoDirectCountries,
            onToggle = component::onToggleGeoCountry,
            onDismiss = { showCountryPicker = false },
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
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
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
                subtitle = if (dnsMode == DnsMode.System) {
                    stringResource(R.string.routing_dns_system_warning)
                } else null,
                value = dnsMode.label(),
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
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(key)
                        expanded = false
                    },
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

@Composable
private fun DirectRoutesCard(
    rules: List<DirectRouteRule>,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (DirectRouteRule) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.large, vertical = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.routing_direct_routes_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.routing_direct_route_add_cd))
                }
            }
            Text(
                stringResource(R.string.routing_direct_routes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.xSmall),
            )
            if (rules.isEmpty()) {
                Text(
                    stringResource(R.string.routing_direct_routes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.large),
                )
            } else {
                rules.forEachIndexed { idx, rule ->
                    DirectRouteRow(
                        rule = rule,
                        onToggle = { onToggle(rule.id, it) },
                        onEdit = { onEdit(rule) },
                        onDelete = { onDelete(rule.id) },
                    )
                    if (idx != rules.lastIndex) DividerRow()
                }
            }
        }
    }
}

@Composable
private fun DirectRouteRow(
    rule: DirectRouteRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rule.cidr.asString(),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (rule.comment.isNotBlank()) {
                Text(
                    rule.comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.routing_direct_route_delete_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DirectRouteEditorDialog(
    existing: DirectRouteRule?,
    onDismiss: () -> Unit,
    onSave: (DirectRouteRule) -> Unit,
) {
    var cidrText by remember { mutableStateOf(existing?.cidr?.asString().orEmpty()) }
    var comment by remember { mutableStateOf(existing?.comment.orEmpty()) }
    val parsed = Cidr.parseOrNull(cidrText)
    val valid = parsed != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.routing_direct_route_add_title
                    else R.string.routing_direct_route_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                OutlinedTextField(
                    value = cidrText,
                    onValueChange = { cidrText = it },
                    label = { Text(stringResource(R.string.routing_direct_route_cidr_label)) },
                    placeholder = { Text("10.0.0.0/8") },
                    singleLine = true,
                    isError = cidrText.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.routing_direct_route_comment_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val cidr = parsed ?: return@TextButton
                    onSave(
                        DirectRouteRule(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            cidr = cidr,
                            comment = comment,
                            enabled = existing?.enabled ?: true,
                            orderIndex = existing?.orderIndex ?: 0,
                        )
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun GeoBypassCard(
    info: ru.shapovalov.bedlam.core.geoip.domain.model.GeoIpDatabaseInfo,
    updateState: GeoIpUpdateState,
    selected: Set<CountryCode>,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Text(
                stringResource(R.string.routing_geo_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.small),
            )
            Text(
                stringResource(R.string.routing_geo_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.large),
            )
            Spacer(Modifier.height(spacing.small))
            when (updateState) {
                is GeoIpUpdateState.Downloading -> {
                    val total = updateState.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (updateState.bytesReceived.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.large, vertical = spacing.small),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.large, vertical = spacing.small),
                        )
                    }
                }
                is GeoIpUpdateState.Failed -> {
                    Text(
                        stringResource(R.string.routing_geo_failed, updateState.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.xSmall),
                    )
                }
                else -> Unit
            }
            if (info.isInstalled) {
                InfoLine(
                    stringResource(R.string.routing_geo_version),
                    info.version ?: "—",
                )
                InfoLine(
                    stringResource(R.string.routing_geo_size),
                    info.sizeBytes?.let { "${it / 1024} KB" } ?: "—",
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.large, vertical = spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    TextButton(onClick = onDownload) { Text(stringResource(R.string.routing_geo_update)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.routing_geo_remove)) }
                }
                NavRow(
                    title = stringResource(R.string.routing_geo_countries_title),
                    value = if (selected.isEmpty()) {
                        stringResource(R.string.routing_geo_countries_none)
                    } else {
                        selected.joinToString(", ") { it.raw }
                    },
                    onClick = onOpenPicker,
                )
            } else {
                TextButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.large, vertical = spacing.small),
                ) { Text(stringResource(R.string.routing_geo_download)) }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.xSmall),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CountryPickerDialog(
    available: List<CountryCode>,
    selected: Set<CountryCode>,
    onToggle: (CountryCode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_geo_countries_title)) },
        text = {
            if (available.isEmpty()) {
                Text(stringResource(R.string.routing_geo_countries_empty))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    available.forEach { country ->
                        val isSelected = country in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(country) }
                                .padding(vertical = MaterialTheme.spacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest
                                    ),
                            )
                            Spacer(Modifier.width(MaterialTheme.spacing.medium))
                            Text(
                                country.raw,
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
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
