package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePreset
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

    val presetStatuses = remember(state.config.sources) {
        val existingAsns = state.config.sources
            .mapNotNull { (it.source as? DirectRouteSource.Asn)?.asn }
            .toSet()
        RoutePresets.ALL.associate { preset ->
            val present = preset.asns.count { it.asn in existingAsns }
            preset.id to PresetStatus(present = present, total = preset.asns.size)
        }
    }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = spacing.large,
                vertical = spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            item(key = "basics") {
                BasicsCard(
                    modifier = Modifier.animateItem(),
                    bypassLan = state.config.bypassLan,
                    ipv6Mode = state.config.ipv6Mode,
                    dnsMode = state.config.dnsMode,
                    customDns = state.config.customDns,
                    onSetBypassLan = component::onSetBypassLan,
                    onSetIpv6Mode = component::onSetIpv6Mode,
                    onSetDnsMode = component::onSetDnsMode,
                    onSetCustomDns = component::onSetCustomDns,
                )
            }

            item(key = "sources-header") {
                SourcesHeaderCard(
                    modifier = Modifier.animateItem(),
                    onAdd = { showAddSource = true },
                    onPresets = { showPresets = true },
                )
            }

            if (state.config.sources.isEmpty()) {
                item(key = "sources-empty") {
                    EmptySourcesRow(modifier = Modifier.animateItem())
                }
            } else {
                items(
                    items = state.config.sources,
                    key = { it.source.id },
                ) { resolved ->
                    SwipeableSourceCard(
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(220),
                            fadeOutSpec = tween(180),
                            placementSpec = tween(220),
                        ),
                        resolved = resolved,
                        isRefreshing = state.isRefreshing,
                        onToggle = component::onSetSourceEnabled,
                        onDelete = component::onRemoveSource,
                    )
                }
            }

            item(key = "reconnect-hint") {
                Text(
                    text = stringResource(R.string.routing_reconnect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = spacing.small),
                )
            }

            item(key = "trailing-space") { Spacer(Modifier.height(spacing.large)) }
        }
    }

    if (showAddSource) {
        AddSourceDialog(
            existingKeys = remember(state.config.sources) {
                state.config.sources.map { it.source.dedupeKey() }.toSet()
            },
            onDismiss = { showAddSource = false },
            onSave = { source ->
                component.onAddSource(source)
                showAddSource = false
            },
        )
    }

    if (showPresets) {
        PresetsDialog(
            statuses = presetStatuses,
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
    modifier: Modifier = Modifier,
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
    val ipv6Options = remember { Ipv6Mode.entries.toList() }
    val dnsOptions = remember { DnsMode.entries.toList() }

    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
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
                options = ipv6Options,
                renderLabel = { it.label() },
                onPick = onSetIpv6Mode,
            )
            DividerRow()
            DropdownRow(
                title = stringResource(R.string.routing_dns_title),
                value = dnsMode.label(),
                subtitle = if (dnsMode == DnsMode.System) stringResource(R.string.routing_dns_system_warning) else null,
                options = dnsOptions,
                renderLabel = { it.label() },
                onPick = onSetDnsMode,
            )
            if (dnsMode == DnsMode.Custom) {
                DividerRow()
                CustomDnsEditor(initial = customDns, onChange = onSetCustomDns)
            }
        }
    }
}

@Composable
private fun SourcesHeaderCard(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
    onPresets: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
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
        }
    }
}

@Composable
private fun EmptySourcesRow(modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Text(
            stringResource(R.string.routing_sources_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.large),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSourceCard(
    modifier: Modifier = Modifier,
    resolved: ResolvedSource,
    isRefreshing: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    val latestResolved by rememberUpdatedState(resolved)
    val latestOnToggle by rememberUpdatedState(onToggle)
    val latestOnDelete by rememberUpdatedState(onDelete)

    var armed by remember { mutableStateOf(true) }

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    latestOnDelete(latestResolved.source.id)
                    true
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (armed) {
                        armed = false
                        latestOnToggle(latestResolved.source.id, !latestResolved.source.enabled)
                    }
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.45f },
    )

    LaunchedEffect(state) {
        snapshotFlow { runCatching { state.requireOffset() }.getOrNull() ?: 0f }
            .collect { offset -> if (offset == 0f) armed = true }
    }

    var expanded by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        SwipeToDismissBox(
            state = state,
            backgroundContent = { SwipeBackground(state.dismissDirection, resolved.source.enabled) },
            gesturesEnabled = !expanded,
            content = {
                SourceRowContent(
                    resolved = resolved,
                    isRefreshing = isRefreshing,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, currentlyEnabled: Boolean) {
    val spacing = MaterialTheme.spacing
    val spec: SwipeBgSpec = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> SwipeBgSpec(
            bg = MaterialTheme.colorScheme.tertiaryContainer,
            fg = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = if (currentlyEnabled) Icons.Default.Clear else Icons.Default.Check,
            label = stringResource(
                if (currentlyEnabled) R.string.routing_swipe_disable else R.string.routing_swipe_enable
            ),
            align = Alignment.CenterStart,
        )
        SwipeToDismissBoxValue.EndToStart -> SwipeBgSpec(
            bg = MaterialTheme.colorScheme.errorContainer,
            fg = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Default.Delete,
            label = stringResource(R.string.routing_sources_delete_cd),
            align = Alignment.CenterEnd,
        )
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(spec.bg)
            .padding(horizontal = spacing.large),
        contentAlignment = spec.align,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(spec.icon, contentDescription = null, tint = spec.fg)
            Spacer(Modifier.width(spacing.small))
            Text(spec.label, style = MaterialTheme.typography.labelLarge, color = spec.fg)
        }
    }
}

private data class SwipeBgSpec(
    val bg: Color,
    val fg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val align: Alignment,
)

@Composable
private fun SourceRowContent(
    resolved: ResolvedSource,
    isRefreshing: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val dimmed = !resolved.source.enabled
    val baseColor = MaterialTheme.colorScheme.surfaceContainerLow
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "chevron-rotation",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(baseColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = spacing.large, vertical = spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (resolved.source.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    ),
            )
            Spacer(Modifier.width(spacing.medium))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KindChip(resolved.source)
                    Spacer(Modifier.width(spacing.small))
                    Text(
                        resolved.source.label(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
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
            Spacer(Modifier.width(spacing.small))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.routing_source_details_collapse_cd
                    else R.string.routing_source_details_expand_cd
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180)),
        ) {
            SourceDetails(resolved)
        }
    }
}

@Composable
private fun SourceDetails(resolved: ResolvedSource) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = spacing.large,
                end = spacing.large,
                bottom = spacing.medium,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (val s = resolved.source) {
            is DirectRouteSource.Asn -> DetailRow(
                label = stringResource(R.string.routing_source_details_asn_label),
                value = "AS${s.asn}",
                monospace = true,
            )
            is DirectRouteSource.Domain -> DetailRow(
                label = stringResource(R.string.routing_source_details_domain_label),
                value = s.hostname,
                monospace = true,
            )
            is DirectRouteSource.Cidr -> DetailRow(
                label = stringResource(R.string.routing_source_details_cidr_label),
                value = s.cidr.asString(),
                monospace = true,
            )
        }

        if (resolved.source.comment.isNotBlank()) {
            DetailRow(
                label = stringResource(R.string.routing_source_details_comment_label),
                value = resolved.source.comment,
            )
        }

        resolved.lastResolvedMillis?.let {
            DetailRow(
                label = stringResource(R.string.routing_source_details_resolved_label),
                value = formatRelative(it),
            )
        }

        resolved.lastError?.let {
            DetailRow(
                label = stringResource(R.string.routing_source_details_error_label),
                value = it,
                valueColor = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(spacing.xSmall))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.routing_source_details_networks_header),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (resolved.cidrs.isNotEmpty()) {
                val v4 = resolved.cidrs.count { it is Cidr.V4 }
                val v6 = resolved.cidrs.count { it is Cidr.V6 }
                Text(
                    stringResource(R.string.routing_source_details_networks_breakdown, v4, v6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (resolved.cidrs.isEmpty()) {
            Text(
                stringResource(R.string.routing_source_details_no_networks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
                resolved.cidrs.forEach { cidr ->
                    Text(
                        cidr.asString(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = if (monospace)
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun KindChip(source: DirectRouteSource) {
    val (label, color) = when (source) {
        is DirectRouteSource.Cidr -> "CIDR" to MaterialTheme.colorScheme.primary
        is DirectRouteSource.Asn -> "ASN" to MaterialTheme.colorScheme.tertiary
        is DirectRouteSource.Domain -> "DOMAIN" to MaterialTheme.colorScheme.secondary
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

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
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(renderLabel(option)) },
                    onClick = { onPick(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun CustomDnsEditor(initial: List<String>, onChange: (List<String>) -> Unit) {
    val spacing = MaterialTheme.spacing
    var text by remember { mutableStateOf(initial.joinToString(", ")) }
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
    existingKeys: Set<String>,
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
            SourceKind.ASN -> v.removePrefix("AS").removePrefix("as").trim().toIntOrNull()?.let {
                DirectRouteSource.Asn(UUID.randomUUID().toString(), it, comment, true, 0)
            }
            SourceKind.DOMAIN -> if (v.contains('.') && !v.contains(' ')) {
                DirectRouteSource.Domain(UUID.randomUUID().toString(), v, comment, true, 0)
            } else null
        }
    }
    val isDuplicate = parsed?.dedupeKey()?.let { it in existingKeys } == true

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
                    isError = (value.isNotEmpty() && parsed == null) || isDuplicate,
                    supportingText = when {
                        isDuplicate -> {
                            { Text(stringResource(R.string.routing_sources_add_duplicate)) }
                        }
                        else -> null
                    },
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
                enabled = parsed != null && !isDuplicate,
                onClick = { parsed?.let(onSave) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private data class PresetStatus(val present: Int, val total: Int) {
    val isFullyAdded: Boolean get() = present == total
    val isPartial: Boolean get() = present in 1 until total
    val isNew: Boolean get() = present == 0
}

@Composable
private fun PresetsDialog(
    statuses: Map<String, PresetStatus>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
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
                    val status = statuses[preset.id] ?: PresetStatus(0, preset.asns.size)
                    PresetRow(preset = preset, status = status, onPick = { onPick(preset.id) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

@Composable
private fun PresetRow(preset: RoutePreset, status: PresetStatus, onPick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val enabled = !status.isFullyAdded
    val (dotColor, statusLabel) = when {
        status.isFullyAdded -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f) to
            stringResource(R.string.routing_preset_added)
        status.isPartial -> MaterialTheme.colorScheme.tertiary to
            stringResource(R.string.routing_preset_partial, status.present, status.total)
        else -> MaterialTheme.colorScheme.primary to
            stringResource(R.string.routing_preset_asn_count, status.total)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .let { if (enabled) it.clickable(onClick = onPick) else it }
            .padding(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.isFullyAdded) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.width(spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                preset.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = dotColor,
            )
        }
    }
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
