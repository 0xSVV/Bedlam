package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.ui.theme.spacing

@Composable
internal fun ProfilesCard(
    profiles: List<Profile>,
    activeProfileId: String?,
    latencies: Map<String, LatencyResult>,
    onSelect: (String) -> Unit,
    onOpenConfig: (String) -> Unit,
    onPingProfile: (String) -> Unit,
    onPingAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (profiles.isEmpty()) return
    val spacing = MaterialTheme.spacing

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.large, end = spacing.small),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_profiles_section),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPingAll) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(SmallIconSize),
                    )
                }
            }
            profiles.forEachIndexed { index, profile ->
                ProfileRow(
                    profile = profile,
                    isActive = profile.id == activeProfileId,
                    latency = latencies[profile.id] ?: LatencyResult.Idle,
                    onClick = { onSelect(profile.id) },
                    onPing = { onPingProfile(profile.id) },
                    onOpenConfig = { onOpenConfig(profile.id) },
                )
                if (index < profiles.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = spacing.large),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    latency: LatencyResult,
    onClick: () -> Unit,
    onPing: () -> Unit,
    onOpenConfig: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val indicatorSize by animateDpAsState(
        targetValue = if (isActive) ProfileDotSize else 0.dp,
        label = "profile-selection-indicator",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ProfileDotSize)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(indicatorSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.width(spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    textAlign = TextAlign.Center,
                    text = profile.name,
                    style = if (isActive) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                LatencyLabel(latency = latency)
            }
            Text(
                text = profile.config.server.address,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenConfig) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.profile_config_open_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LatencyLabel(latency: LatencyResult) {
    val text = when (latency) {
        LatencyResult.Idle -> return
        LatencyResult.Measuring -> "..."
        is LatencyResult.Success -> "${latency.ms} ms"
        LatencyResult.Unreachable -> "—"
    }
    val color = when (latency) {
        is LatencyResult.Success -> when {
            latency.ms < 100 -> MaterialTheme.colorScheme.primary
            latency.ms < 300 -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.error
        }
        LatencyResult.Unreachable -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Spacer(Modifier.width(MaterialTheme.spacing.small))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

private val SmallIconSize = 20.dp
private val ProfileDotSize = 10.dp
