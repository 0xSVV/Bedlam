package ru.shapovalov.bedlam.feature.dashboard.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponent
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(component: DashboardComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = MaterialTheme.spacing

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        component.onDismissError()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DashboardTopBar(
                onImport = {
                    val pasted = clipboard.getText()?.text.orEmpty()
                    component.onImportFromClipboard(pasted)
                },
                isImporting = state.isImporting,
            )

            Spacer(Modifier.height(spacing.small))

            ConnectionHero(
                connectionState = state.connectionState,
                connectedSinceMillis = state.connectedSinceMillis,
                hasActiveProfile = state.activeProfile != null,
                onToggle = component::onToggleConnection,
            )

            Spacer(Modifier.height(spacing.xLarge))

            ProfilesCard(
                profiles = state.profiles,
                activeProfileId = state.activeProfileId,
                onSelect = component::onSelectProfile,
                onDelete = component::onDeleteProfile,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.large),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun DashboardTopBar(
    onImport: () -> Unit,
    isImporting: Boolean,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Bedlam",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
            onClick = onImport,
            enabled = !isImporting,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Default.Add, contentDescription = "Import from clipboard")
            }
        }
    }
}

@Composable
private fun ConnectionHero(
    connectionState: ConnectionState,
    connectedSinceMillis: Long?,
    hasActiveProfile: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Reconnecting

    val elapsedSeconds = remember(connectedSinceMillis) { mutableStateOf(0L) }
    LaunchedEffect(connectedSinceMillis) {
        if (connectedSinceMillis == null) {
            elapsedSeconds.value = 0L
        } else {
            while (true) {
                elapsedSeconds.value = (System.currentTimeMillis() - connectedSinceMillis) / 1000
                delay(1000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Connection Time",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.xSmall))
        Text(
            text = formatDuration(elapsedSeconds.value),
            style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.large))

        LargeFloatingActionButton(
            onClick = onToggle,
            shape = RoundedCornerShape(spacing.xLarge),
            containerColor = when {
                isConnected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier
                .size(96.dp)
                .semantics { contentDescription = if (isConnected) "Disconnect" else "Connect" },
        ) {
            when {
                isConnecting -> CircularProgressIndicator(modifier = Modifier.size(40.dp))
                isConnected -> PauseGlyph(
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
                else -> Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(spacing.medium))
        AssistChip(
            onClick = {},
            label = { Text(connectionState.display()) },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = when (connectionState) {
                    is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                    is ConnectionState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        )
        if (!hasActiveProfile && connectionState is ConnectionState.Disconnected) {
            Spacer(Modifier.height(spacing.small))
            Text(
                text = "Import a profile from clipboard to get started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfilesCard(
    profiles: List<Profile>,
    activeProfileId: String?,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (profiles.isEmpty()) return
    val spacing = MaterialTheme.spacing

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
            Text(
                text = "LOCAL PROFILES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.small),
            )
            LazyColumn {
                items(profiles, key = Profile::id) { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onClick = { onSelect(profile.id) },
                        onDelete = { onDelete(profile.id) },
                    )
                    if (profile != profiles.last()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = spacing.large),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                ),
        )
        Spacer(Modifier.width(spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "HYSTERIA · ${profile.config.server.server}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete profile",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tint),
        )
    }
}

private fun ConnectionState.display(): String = when (this) {
    is ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting (#$attempt)"
    is ConnectionState.Error -> "Error: $message"
}

private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
