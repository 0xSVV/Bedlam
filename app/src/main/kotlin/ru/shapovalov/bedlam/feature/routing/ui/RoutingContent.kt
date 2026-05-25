package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.routing.data.RoutePresets
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.feature.routing.presentation.RoutingComponent
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
                title = {
                    Text(
                        text = stringResource(R.string.routing_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
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
private fun SourcesHeaderCard(
    modifier: Modifier = Modifier,
    onAdd: () -> Unit,
    onPresets: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.large, vertical = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.routing_sources_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Spacer(Modifier.height(spacing.xSmall))
                    Text(
                        text = stringResource(R.string.routing_sources_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.routing_sources_add_cd),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.xSmall),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                ElevatedAssistChip(
                    onClick = onPresets,
                    label = { Text(stringResource(R.string.routing_presets_chip)) },
                )
            }
        }
    }
}

@Composable
private fun EmptySourcesRow(modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Text(
            text = stringResource(R.string.routing_sources_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MaterialTheme.spacing.large),
        )
    }
}
