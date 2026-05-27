package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.util.formatDuration
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.ConnectionState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectionHero(
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
                    modifier = Modifier.size(ChipTrailingIconSize),
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
            .shadow(ConnectionFabShadowElevation, MaterialTheme.shapes.extraLarge, clip = false)
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

@Composable
private fun ConnectionState.displayText(): String = when (this) {
    is ConnectionState.Disconnected -> stringResource(R.string.dashboard_state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.dashboard_state_connecting)
    is ConnectionState.Connected -> stringResource(R.string.dashboard_state_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.dashboard_state_reconnecting, attempt)
    is ConnectionState.Error -> stringResource(R.string.dashboard_state_error)
}

private val ConnectionMorphAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow,
    visibilityThreshold = 0.1f,
)

private val ConnectionFabContainerSize = 96.dp
private val ConnectionFabShadowElevation = 6.dp
private val ChipTrailingIconSize = 18.dp
