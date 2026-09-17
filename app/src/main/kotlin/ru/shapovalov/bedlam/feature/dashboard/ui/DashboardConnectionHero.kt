package ru.shapovalov.bedlam.feature.dashboard.ui

import android.graphics.BlurMaskFilter
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.graphics.shapes.Morph
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.util.formatDuration
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.ConnectionState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectionHero(
    connectionState: ConnectionState,
    connectedSinceMillis: Long?,
    hasActiveProfile: Boolean,
    pendingSwitchName: String?,
    onToggle: () -> Unit,
    onConfirmSwitch: () -> Unit,
    onCancelSwitch: () -> Unit,
    onOpenSession: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val colorScheme = MaterialTheme.colorScheme
    val motionScheme = MaterialTheme.motionScheme
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Reconnecting
    val isError = connectionState is ConnectionState.Error
    val isConfirming = pendingSwitchName != null
    val buttonMode = when {
        isConfirming -> ConnectionButtonMode.ConfirmSwitch
        isConnecting -> ConnectionButtonMode.Loading
        else -> ConnectionButtonMode.Resting
    }

    val buttonMorph = remember {
        ConnectionButtonMorph(
            restingShape = MaterialShapes.Square,
            loadingShapes = LoadingIndicatorDefaults.IndeterminateIndicatorPolygons,
            confirmShape = MaterialShapes.Cookie9Sided,
            cancelShape = MaterialShapes.Circle,
            connecting = isConnecting,
            confirming = isConfirming,
        )
    }

    LaunchedEffect(buttonMode) {
        when (buttonMode) {
            ConnectionButtonMode.Loading -> buttonMorph.animateLoading()
            ConnectionButtonMode.Resting -> buttonMorph.settle()
            ConnectionButtonMode.ConfirmSwitch -> buttonMorph.split()
        }
    }

    val connectionButtonColor by animateColorAsState(
        targetValue = when {
            isConfirming || isConnecting -> colorScheme.primary
            isError -> colorScheme.errorContainer
            isConnected -> colorScheme.primaryContainer
            else -> colorScheme.surfaceContainerHigh
        },
        label = "connection-button-color",
    )
    val connectionButtonContentColor by animateColorAsState(
        targetValue = when {
            isConfirming -> colorScheme.onPrimary
            isError -> colorScheme.onErrorContainer
            else -> colorScheme.onSurface
        },
        label = "connection-button-content-color",
    )
    val cancelButtonColor = colorScheme.surfaceContainerHigh

    val lifecycleOwner = LocalLifecycleOwner.current
    val elapsedSeconds = remember(connectedSinceMillis) {
        mutableLongStateOf(connectedSinceMillis?.let(::secondsSince) ?: 0L)
    }
    LaunchedEffect(connectedSinceMillis, lifecycleOwner) {
        if (connectedSinceMillis == null) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                elapsedSeconds.longValue = secondsSince(connectedSinceMillis)
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
        AnimatedContent(
            targetState = isConfirming,
            transitionSpec = { heroTextTransition(motionScheme, forward = targetState) },
            contentAlignment = Alignment.Center,
            label = "hero-caption",
        ) { confirming ->
            Text(
                text = stringResource(
                    if (confirming) {
                        R.string.dashboard_confirm_switch_title
                    } else {
                        R.string.dashboard_connection_time
                    }
                ),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = colorScheme.onSurface,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(spacing.xSmall))
        AnimatedContent(
            targetState = pendingSwitchName,
            transitionSpec = { heroTextTransition(motionScheme, forward = targetState != null) },
            contentAlignment = Alignment.Center,
            label = "hero-headline",
        ) { name ->
            val style = MaterialTheme.typography.displayMediumEmphasized.copy(
                fontFeatureSettings = "tnum",
            )
            Text(
                text = name ?: formatDuration(elapsedSeconds.longValue),
                style = style,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                autoSize = name?.let {
                    TextAutoSize.StepBased(
                        minFontSize = HeroNameMinFontSize,
                        maxFontSize = style.fontSize,
                    )
                },
            )
        }
        Spacer(Modifier.height(spacing.large))

        val toggleCd = stringResource(
            when {
                isConnected || isConnecting -> R.string.action_disconnect
                isError -> R.string.action_reconnect
                else -> R.string.action_connect
            }
        )
        val confirmCd = pendingSwitchName?.let { stringResource(R.string.dashboard_confirm_switch_cd, it) }
        val cancelCd = stringResource(R.string.dashboard_cancel_switch_cd)
        val stateText = connectionState.displayText()
        val iconRes = when {
            isConfirming -> R.drawable.ic_check
            isConnected -> R.drawable.ic_pause
            else -> R.drawable.ic_power_settings_new
        }
        Box(
            modifier = Modifier.size(
                width = ConnectionFabContainerSize * 2 + ConnectionFabSplitGap,
                height = ConnectionFabContainerSize,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (buttonMorph.isSplit) {
                ConnectionFab(
                    shape = buttonMorph.cancelButton,
                    containerColor = { lerp(connectionButtonColor, cancelButtonColor, buttonMorph.split) },
                    alpha = { (buttonMorph.split * CancelButtonFadeSpeed).coerceAtMost(1f) },
                    onClick = onCancelSwitch,
                    modifier = Modifier
                        .offset { IntOffset(-(ConnectionFabSplitOffset.toPx() * buttonMorph.split).roundToInt(), 0) }
                        .size(ConnectionFabContainerSize)
                        .semantics { contentDescription = cancelCd },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                        tint = colorScheme.onSurface,
                    )
                }
            }
            ConnectionFab(
                shape = buttonMorph.button,
                containerColor = { connectionButtonColor },
                alpha = { 1f },
                onClick = if (isConfirming) onConfirmSwitch else onToggle,
                modifier = Modifier
                    .zIndex(1f)
                    .offset { IntOffset((ConnectionFabSplitOffset.toPx() * buttonMorph.split).roundToInt(), 0) }
                    .size(ConnectionFabContainerSize)
                    .semantics {
                        contentDescription = confirmCd ?: toggleCd
                        stateDescription = stateText
                    },
            ) {
                ConnectionButtonIcon(
                    visible = buttonMorph.showIcon,
                    iconRes = iconRes,
                    tint = connectionButtonContentColor,
                    motionScheme = motionScheme,
                )
            }
        }
        Spacer(Modifier.height(spacing.large))
        val chipLabelColor = when (connectionState) {
            is ConnectionState.Connected -> colorScheme.primary
            is ConnectionState.Error -> colorScheme.error
            else -> colorScheme.onSurfaceVariant
        }
        val openSessionCd = stringResource(R.string.dashboard_open_session_cd)
        ElevatedAssistChip(
            onClick = onOpenSession,
            label = {
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            },
            trailingIcon = {
                Icon(
                    painterResource(R.drawable.ic_keyboard_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(ChipTrailingIconSize),
                )
            },
            colors = AssistChipDefaults.elevatedAssistChipColors(
                labelColor = chipLabelColor,
                trailingIconContentColor = chipLabelColor,
            ),
            modifier = Modifier.semantics {
                onClick(label = openSessionCd) {
                    onOpenSession()
                    true
                }
                liveRegion = LiveRegionMode.Polite
            },
        )
        if (!hasActiveProfile && connectionState is ConnectionState.Disconnected) {
            Spacer(Modifier.height(spacing.small))
            Text(
                text = stringResource(R.string.dashboard_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        if (connectionState is ConnectionState.Error) {
            Spacer(Modifier.height(spacing.small))
            Text(
                text = connectionState.message,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.error,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class ConnectionButtonMode { Resting, Loading, ConfirmSwitch }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionButtonIcon(
    visible: Boolean,
    @DrawableRes iconRes: Int,
    tint: Color,
    motionScheme: MotionScheme,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        AnimatedContent(
            targetState = iconRes,
            transitionSpec = {
                val enter = fadeIn(motionScheme.defaultEffectsSpec()) +
                        scaleIn(motionScheme.defaultSpatialSpec(), initialScale = 0.6f)
                val exit = fadeOut(motionScheme.fastEffectsSpec()) +
                        scaleOut(motionScheme.defaultSpatialSpec(), targetScale = 0.6f)
                enter.togetherWith(exit)
            },
            label = "connection-icon",
        ) { icon ->
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                tint = tint,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun heroTextTransition(motionScheme: MotionScheme, forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val enter = fadeIn(motionScheme.defaultEffectsSpec()) +
            slideInVertically(motionScheme.defaultSpatialSpec()) { direction * it / 2 }
    val exit = fadeOut(motionScheme.fastEffectsSpec()) +
            slideOutVertically(motionScheme.defaultSpatialSpec()) { -direction * it / 2 }
    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionFab(
    shape: MorphingShape,
    containerColor: () -> Color,
    alpha: () -> Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val morph = remember(shape.fromShape, shape.toShape) { Morph(shape.fromShape, shape.toShape) }
    val morphClip = remember(morph) {
        GenericShape { size, _ ->
            val p = morph.toPath(progress = shape.progress)
            p.transform(Matrix().apply { scale(x = size.width, y = size.height) })
            p.translate(size.center - p.getBounds().center)
            addPath(p)
        }
    }
    val density = LocalDensity.current
    val shadowPaint = remember(density) {
        Paint().apply {
            color = ConnectionFabShadowColor
            asFrameworkPaint().maskFilter = BlurMaskFilter(
                with(density) { ConnectionFabShadowBlur.toPx() },
                BlurMaskFilter.Blur.NORMAL,
            )
        }
    }
    val contentPaint = remember { Paint() }
    Box(
        modifier = modifier
            .drawWithContent {
                val opacity = alpha()
                val path = morph.toPath(progress = shape.progress)
                path.transform(Matrix().apply { scale(x = size.width, y = size.height) })
                path.translate(size.center - path.getBounds().center)
                shadowPaint.alpha = ConnectionFabShadowColor.alpha * opacity
                translate(top = ConnectionFabShadowOffset.toPx()) {
                    drawIntoCanvas { it.drawPath(path, shadowPaint) }
                }
                drawPath(path, color = containerColor(), alpha = opacity)
                if (opacity < 1f) {
                    contentPaint.alpha = opacity
                    drawIntoCanvas { canvas ->
                        canvas.withSaveLayer(size.toRect(), contentPaint) { drawContent() }
                    }
                } else {
                    drawContent()
                }
            }
            .clip(morphClip)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Button,
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
    is ConnectionState.Reconnecting -> stringResource(
        R.string.dashboard_state_reconnecting,
        attempt
    )

    is ConnectionState.Error -> stringResource(R.string.dashboard_state_error)
}

private fun secondsSince(elapsedRealtimeMillis: Long): Long =
    (SystemClock.elapsedRealtime() - elapsedRealtimeMillis) / 1000

private val HeroNameMinFontSize = 24.sp
private const val CancelButtonFadeSpeed = 3f
private val ConnectionFabContainerSize = 96.dp
private val ConnectionFabSplitGap = 16.dp
private val ConnectionFabSplitOffset = (ConnectionFabContainerSize + ConnectionFabSplitGap) / 2
private val ConnectionFabShadowBlur = 10.dp
private val ConnectionFabShadowOffset = 3.dp
private val ConnectionFabShadowColor = Color.Black.copy(alpha = 0.3f)
private val ChipTrailingIconSize = 18.dp
