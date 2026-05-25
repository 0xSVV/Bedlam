package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardComponent
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardContent(component: DashboardComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = MaterialTheme.spacing

    val errorText = state.error?.resolve()
    LaunchedEffect(errorText) {
        val msg = errorText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        component.onDismissError()
    }
    val connectionErrorText = (state.connectionState as? ConnectionState.Error)?.let {
        stringResource(R.string.dashboard_connection_error, it.message)
    }
    LaunchedEffect(connectionErrorText) {
        val msg = connectionErrorText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
    }

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DashboardTopBar(
                onImport = {
                    scope.launch {
                        val pasted = clipboard.getClipEntry()
                            ?.clipData
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                        component.onImportFromClipboard(pasted)
                    }
                },
                isImporting = state.isImporting,
            )
            ConnectionHero(
                connectionState = state.connectionState,
                connectedSinceMillis = state.connectedSinceMillis,
                hasActiveProfile = state.activeProfile != null,
                onToggle = component::onToggleConnection,
                onOpenSession = component::onOpenSession,
            )
            Spacer(Modifier.height(spacing.xLarge))
            ProfilesCard(
                profiles = state.profiles,
                activeProfileId = state.activeProfileId,
                latencies = state.latencies,
                onSelect = component::onSelectProfile,
                onOpenConfig = component::onOpenProfileConfig,
                onPingProfile = component::onPingProfile,
                onPingAll = component::onPingAllProfiles,
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
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.dashboard_action_import_cd),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionHero(
    connectionState: ConnectionState,
    connectedSinceMillis: Long?,
    hasActiveProfile: Boolean,
    onToggle: () -> Unit,
    onOpenSession: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.Reconnecting
    val isError = connectionState is ConnectionState.Error

    val restingButtonShape = MaterialShapes.Square
    val loadingButtonShapes = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons
    var fromButtonShape by remember { mutableStateOf(restingButtonShape) }
    var toButtonShape by remember { mutableStateOf(restingButtonShape) }
    var showButtonIcon by remember { mutableStateOf(!isConnecting) }
    val buttonMorphProgress = remember { Animatable(0f) }
    val morph = remember(fromButtonShape, toButtonShape) {
        Morph(fromButtonShape, toButtonShape)
    }

    LaunchedEffect(isConnecting) {
        suspend fun returnToCurrentShape() {
            if (fromButtonShape != toButtonShape && buttonMorphProgress.value > 0f) {
                buttonMorphProgress.animateTo(0f, ConnectionMorphAnimationSpec)
                toButtonShape = fromButtonShape
                buttonMorphProgress.snapTo(0f)
            }
        }

        suspend fun morphTo(nextShape: RoundedPolygon) {
            if (fromButtonShape == nextShape) return
            toButtonShape = nextShape
            buttonMorphProgress.snapTo(0f)
            buttonMorphProgress.animateTo(1f, ConnectionMorphAnimationSpec)
            fromButtonShape = nextShape
            toButtonShape = nextShape
            buttonMorphProgress.snapTo(0f)
        }

        if (isConnecting) {
            showButtonIcon = false
            while (true) {
                loadingButtonShapes.forEach { morphTo(it) }
            }
        } else {
            showButtonIcon = true
            returnToCurrentShape()
            morphTo(restingButtonShape)
        }
    }

    val connectionButtonColor by animateColorAsState(
        targetValue = when {
            isConnecting -> MaterialTheme.colorScheme.primary
            isError -> MaterialTheme.colorScheme.errorContainer
            isConnected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "connection-button-color",
    )
    val connectionButtonContentColor by animateColorAsState(
        targetValue = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "connection-button-content-color",
    )

    val elapsedSeconds = remember(connectedSinceMillis) { mutableLongStateOf(0L) }
    LaunchedEffect(connectedSinceMillis) {
        if (connectedSinceMillis == null) {
            elapsedSeconds.longValue = 0L
        } else {
            while (true) {
                elapsedSeconds.longValue = (System.currentTimeMillis() - connectedSinceMillis) / 1000
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
            text = stringResource(R.string.dashboard_connection_time),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.xSmall))
        Text(
            text = formatDuration(elapsedSeconds.value),
            style = MaterialTheme.typography.displayMediumEmphasized.copy(
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.large))

        val toggleCd = stringResource(
            when {
                isConnected || isConnecting -> R.string.action_disconnect
                isError -> R.string.action_reconnect
                else -> R.string.action_connect
            }
        )
        ConnectionFab(
            morph = morph,
            progress = { buttonMorphProgress.value },
            containerColor = connectionButtonColor,
            onClick = onToggle,
            modifier = Modifier
                .size(ConnectionFabContainerSize)
                .semantics { contentDescription = toggleCd },
        ) {
            AnimatedVisibility(
                visible = showButtonIcon,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Icon(
                    painter = painterResource(
                        if (isConnected) R.drawable.ic_pause else R.drawable.ic_power
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                    tint = connectionButtonContentColor,
                )
            }
        }
        Spacer(Modifier.height(spacing.large))
        val chipLabelColor = when (connectionState) {
            is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
            is ConnectionState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val openSessionCd = stringResource(R.string.dashboard_open_session_cd)
        ElevatedAssistChip(
            onClick = onOpenSession,
            label = {
                Text(
                    text = connectionState.displayText(),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            },
            trailingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            colors = AssistChipDefaults.elevatedAssistChipColors(
                labelColor = chipLabelColor,
                trailingIconContentColor = chipLabelColor,
            ),
            modifier = Modifier.semantics { contentDescription = openSessionCd },
        )
        if (!hasActiveProfile && connectionState is ConnectionState.Disconnected) {
            Spacer(Modifier.height(spacing.small))
            Text(
                text = stringResource(R.string.dashboard_empty_hint),
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
                horizontalArrangement = Arrangement.SpaceBetween,
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
                        modifier = Modifier.size(20.dp),
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
        targetValue = if (isActive) 10.dp else 0.dp,
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
                .size(10.dp)
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

@Composable
private fun ConnectionState.displayText(): String = when (this) {
    is ConnectionState.Disconnected -> stringResource(R.string.dashboard_state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.dashboard_state_connecting)
    is ConnectionState.Connected -> stringResource(R.string.dashboard_state_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.dashboard_state_reconnecting, attempt)
    is ConnectionState.Error -> stringResource(R.string.dashboard_state_error)
}

@Composable
private fun DashboardStore.ErrorReason.resolve(): String = when (this) {
    DashboardStore.ErrorReason.NoActiveProfile -> stringResource(R.string.dashboard_error_no_profile)
    DashboardStore.ErrorReason.ClipboardEmpty -> stringResource(R.string.dashboard_error_clipboard_empty)
    is DashboardStore.ErrorReason.ImportFailed ->
        cause ?: stringResource(R.string.dashboard_error_import_failed)
}

private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionFab(
    morph: Morph,
    progress: () -> Float,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val morphClip = remember(morph) {
        GenericShape { size, _ ->
            val p = morph.toPath(progress = progress())
            p.transform(Matrix().apply { scale(x = size.width, y = size.height) })
            p.translate(size.center - p.getBounds().center)
            addPath(p)
        }
    }
    Box(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(28.dp), clip = false)
            .drawWithContent {
                val path = morph.toPath(progress = progress())
                path.transform(Matrix().apply { scale(x = size.width, y = size.height) })
                path.translate(size.center - path.getBounds().center)
                drawPath(path, color = containerColor)
                drawContent()
            }
            .clip(morphClip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private val ConnectionMorphAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.1f,
)

private val ConnectionFabContainerSize = 96.dp
