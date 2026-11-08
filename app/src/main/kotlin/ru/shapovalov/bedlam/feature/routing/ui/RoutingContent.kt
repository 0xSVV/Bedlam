package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
    val snackbarHostState = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.routing_source_removed)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(state.removedSource) {
        val removed = state.removedSource ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = removedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> component.onUndoRemoveSource(removed.source.id)
            SnackbarResult.Dismissed -> component.onDismissRemoval(removed.source.id)
        }
    }
    var showAddSource by rememberSaveable { mutableStateOf(false) }
    var showPresets by rememberSaveable { mutableStateOf(false) }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
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
                            painterResource(R.drawable.ic_arrow_back),
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
                            painterResource(R.drawable.ic_refresh),
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
            item(key = "bypass-lan", contentType = "bypass-lan") {
                BypassLanCard(
                    bypassLan = state.config.bypassLan,
                    onSetBypassLan = component::onSetBypassLan,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "ipv6", contentType = "ipv6") {
                Ipv6Card(
                    ipv6Mode = state.config.ipv6Mode,
                    onSetIpv6Mode = component::onSetIpv6Mode,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "mtu", contentType = "mtu") {
                MtuCard(
                    mtu = state.config.mtu,
                    onSetMtu = component::onSetMtu,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "direct-dns", contentType = "direct-dns") {
                DirectDnsCard(modifier = Modifier.animateItem())
            }
            item(key = "remote-dns", contentType = "remote-dns") {
                RemoteDnsCard(
                    dnsMode = state.config.dnsMode,
                    dnsTransport = state.config.dnsTransport,
                    customDns = state.config.customDns,
                    onSetDnsMode = component::onSetDnsMode,
                    onSetDnsTransport = component::onSetDnsTransport,
                    onSetCustomDns = component::onSetCustomDns,
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "dns-cache", contentType = "dns-cache") {
                DnsCacheCard(
                    enabled = state.config.dnsCacheEnabled,
                    onSetEnabled = component::onSetDnsCacheEnabled,
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "sources-header", contentType = "sources-header") {
                SourcesHeaderCard(
                    modifier = Modifier.animateItem(),
                    onAdd = { showAddSource = true },
                    onPresets = { showPresets = true },
                )
            }

            if (state.config.sources.isEmpty()) {
                item(key = "sources-empty", contentType = "sources-empty") {
                    EmptySourcesRow(modifier = Modifier.animateItem())
                }
            } else {
                items(
                    items = state.config.sources,
                    key = { it.source.id },
                    contentType = { "route-source" },
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

            item(key = "reconnect-hint", contentType = "reconnect-hint") {
                Text(
                    text = stringResource(R.string.routing_reconnect_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = spacing.small),
                )
            }

            item(key = "trailing-space", contentType = "trailing-space") { Spacer(Modifier.height(spacing.large)) }
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
internal fun SourcesHeaderCard(
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
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(R.string.routing_sources_add_cd),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.xSmall),
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
            ) {
                AssistChip(
                    onClick = onPresets,
                    label = { Text(stringResource(R.string.routing_presets_chip)) },
                )
            }
        }
    }
}

@Composable
internal fun EmptySourcesRow(modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Text(
            text = stringResource(R.string.routing_sources_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MaterialTheme.spacing.large),
        )
    }
}
