package ru.shapovalov.bedlam.feature.logs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.logs.presentation.LogsComponent
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LogsContent(component: LogsComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            LevelFilterRow(
                selected = state.minLevel,
                onSelect = component::onChangeMinLevel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val emptyReason = state.emptyReason()
                if (emptyReason != null) {
                    EmptyState(
                        text = when (emptyReason) {
                            LogsEmptyReason.Filtered -> stringResource(
                                R.string.logs_empty_filtered,
                                state.minLevel.label(),
                            )
                            LogsEmptyReason.Paused -> stringResource(R.string.logs_empty_paused)
                            LogsEmptyReason.Idle -> stringResource(R.string.logs_empty_idle)
                        },
                    )
                } else {
                    LogList(
                        entries = state.visibleEntries,
                        droppedCount = state.droppedCount,
                    )
                }
            }
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        LogsActionsMenu(
            isPaused = state.isPaused,
            onTogglePause = component::onTogglePause,
            onClear = component::onClear,
            onShare = {
                val entries = state.visibleEntries
                val minLevel = state.minLevel
                val droppedCount = state.droppedCount
                scope.launch { context.shareLog(entries, minLevel, droppedCount) }
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogsActionsMenu(
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val pauseLabel = stringResource(
        if (isPaused) R.string.logs_action_resume_cd else R.string.logs_action_pause_cd
    )
    val openLabel = stringResource(R.string.logs_action_menu_open_cd)
    val closeLabel = stringResource(R.string.logs_action_menu_close_cd)

    BackHandler(expanded) { expanded = false }

    FloatingActionButtonMenu(
        modifier = modifier,
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { expanded = !expanded },
                modifier = Modifier.semantics {
                    traversalIndex = -1f
                    stateDescription = if (expanded) closeLabel else openLabel
                },
            ) {
                val icon by remember {
                    derivedStateOf {
                        if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.MoreVert
                    }
                }
                Icon(
                    painter = rememberVectorPainter(icon),
                    contentDescription = if (expanded) closeLabel else openLabel,
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onTogglePause()
            },
            icon = {
                if (isPaused) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                } else {
                    PauseGlyph(
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            text = { Text(pauseLabel) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onShare()
            },
            icon = { Icon(Icons.Default.Share, contentDescription = null) },
            text = { Text(stringResource(R.string.logs_action_share_cd)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onClear()
            },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            text = { Text(stringResource(R.string.logs_action_clear_cd)) },
        )
    }
}

@Composable
private fun LevelFilterRow(
    selected: LogLevel,
    onSelect: (LogLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        LogLevel.entries.forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                modifier = Modifier.semantics { role = Role.RadioButton },
                label = {
                    Text(
                        text = level.label(),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = level.accentColor().copy(alpha = 0.20f),
                    selectedLabelColor = level.accentColor(),
                ),
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogList(entries: List<LogEntry>, droppedCount: Long) {
    val listState = rememberLazyListState()
    var followTail by rememberSaveable { mutableStateOf(true) }
    val tailIndex = logTailIndex(entries.size, droppedCount)
    val tailSeq = entries.lastOrNull()?.seq
    val currentTailIndex by rememberUpdatedState(tailIndex)
    val tailSlopPx by rememberUpdatedState(
        with(LocalDensity.current) { FollowTailSlop.roundToPx() },
    )

    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect { followTail = false }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { inProgress -> !inProgress }
            .collect {
                if (!followTail) {
                    followTail = !listState.canScrollForward ||
                            listState.layoutInfo.isAtTail(currentTailIndex, tailSlopPx)
                }
            }
    }
    LaunchedEffect(tailSeq, tailIndex, followTail) {
        if (!followTail || tailIndex < 0) return@LaunchedEffect
        if (listState.layoutInfo.shouldSnapTo(tailIndex)) {
            listState.scrollToItem(tailIndex)
        } else {
            listState.animateScrollToItem(tailIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MaterialTheme.spacing.large,
            top = MaterialTheme.spacing.small,
            end = MaterialTheme.spacing.large,
            bottom = LogListBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(LogRowSpacing),
    ) {
        if (droppedCount > 0L) {
            item(key = DroppedNoticeKey) { DroppedNotice(droppedCount) }
        }
        items(entries, key = { it.seq }) { entry ->
            LogRow(entry)
        }
    }
}

private val FollowTailSlop = 8.dp

@Composable
private fun LogRow(entry: LogEntry) {
    val spacing = MaterialTheme.spacing
    val accent = entry.level.accentColor()
    val timestamp = remember(entry.timestampMillis) {
        TIMESTAMP_FORMAT.format(Date(entry.timestampMillis))
    }
    val monoSmall = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LogRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(LogAccentBarWidth)
                .height(LogAccentBarHeight)
                .clip(RoundedCornerShape(LogAccentBarCorner))
                .background(accent),
        )
        Spacer(Modifier.width(spacing.small))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = timestamp,
                    style = monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(Modifier.width(spacing.small))
                LevelChip(level = entry.level)
                if (entry.source.isNotBlank()) {
                    Spacer(Modifier.width(spacing.small))
                    Text(
                        text = entry.source,
                        style = monoSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = entry.message,
                style = monoSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LevelChip(level: LogLevel) {
    val color = level.accentColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = level.label(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = color,
        )
    }
}

@Composable
private fun LogLevel.accentColor(): Color = when (this) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun LogLevel.label(): String = stringResource(
    when (this) {
        LogLevel.DEBUG -> R.string.logs_level_debug
        LogLevel.INFO -> R.string.logs_level_info
        LogLevel.WARN -> R.string.logs_level_warn
        LogLevel.ERROR -> R.string.logs_level_error
    }
)

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PauseBarSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(PauseBarWidth)
                .height(PauseBarHeight)
                .clip(RoundedCornerShape(PauseBarCorner))
                .background(tint),
        )
        Box(
            modifier = Modifier
                .width(PauseBarWidth)
                .height(PauseBarHeight)
                .clip(RoundedCornerShape(PauseBarCorner))
                .background(tint),
        )
    }
}

private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private val LogListBottomPadding = 88.dp
private val LogRowSpacing = 2.dp
private val LogAccentBarWidth = 3.dp
private val LogAccentBarHeight = 32.dp
private val LogAccentBarCorner = 2.dp
private val PauseBarSpacing = 3.dp
private val PauseBarWidth = 4.dp
private val PauseBarHeight = 14.dp
private val PauseBarCorner = 1.dp

private const val DroppedNoticeKey = "dropped-notice"

@Composable
private fun DroppedNotice(droppedCount: Long) {
    Text(
        text = stringResource(R.string.logs_dropped_notice, droppedCount),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
