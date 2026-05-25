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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableSourceCard(
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
        shape = SourceCardShape,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                    .size(SourceIndicatorSize)
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
                        text = resolved.source.label(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (resolved.source.comment.isNotBlank()) {
                    Spacer(Modifier.height(spacing.xSmall))
                    Text(
                        text = resolved.source.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(spacing.xSmall))
                Text(
                    text = resolutionSummary(resolved, isRefreshing),
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
        resolved.lastError?.let {
            DetailRow(
                label = stringResource(R.string.routing_source_details_error_label),
                value = it,
                valueColor = MaterialTheme.colorScheme.error,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.routing_source_details_networks_header),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (resolved.cidrs.isNotEmpty()) {
                val v4 = resolved.cidrs.count { it is Cidr.V4 }
                val v6 = resolved.cidrs.count { it is Cidr.V6 }
                Text(
                    text = stringResource(R.string.routing_source_details_networks_breakdown, v4, v6),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (resolved.cidrs.isEmpty()) {
            Text(
                text = stringResource(R.string.routing_source_details_no_networks),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xSmall)) {
                resolved.cidrs.forEach { cidr ->
                    Text(
                        text = cidr.asString(),
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
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DetailLabelWidth),
        )
        Text(
            text = value,
            style = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
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
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = KindChipHPad, vertical = KindChipVPad),
    ) {
        Text(
            text = label,
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

private val SourceCardShape = RoundedCornerShape(20.dp)
private val DetailLabelWidth = 96.dp
private val SourceIndicatorSize = 10.dp
private val KindChipHPad = 8.dp
private val KindChipVPad = 2.dp
