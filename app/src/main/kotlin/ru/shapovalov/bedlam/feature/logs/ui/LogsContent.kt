package ru.shapovalov.bedlam.feature.logs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.logs.presentation.LogsComponent
import ru.shapovalov.bedlam.feature.logs.presentation.LogsStore
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsContent(component: LogsComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing
    val terminalBackground = MaterialTheme.colorScheme.surfaceContainerLowest

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.logs_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    val pauseCd = stringResource(
                        if (state.isPaused) R.string.logs_action_resume_cd
                        else R.string.logs_action_pause_cd
                    )
                    IconButton(
                        onClick = component::onTogglePause,
                        modifier = Modifier.semantics { contentDescription = pauseCd },
                    ) {
                        if (state.isPaused) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                        } else {
                            PauseGlyph(
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = component::onClear, enabled = state.liveEntries.isNotEmpty()) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.logs_action_clear_cd),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LevelFilterRow(
                selected = state.minLevel,
                onSelect = component::onChangeMinLevel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.large, vertical = spacing.small),
            )

            TerminalSurface(
                background = terminalBackground,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.large)
                    .padding(bottom = spacing.large),
            ) {
                val visible = state.visibleEntries
                if (visible.isEmpty()) {
                    EmptyState(isPaused = state.isPaused)
                } else {
                    LogList(
                        entries = visible,
                        isPaused = state.isPaused,
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelFilterRow(
    selected: LogLevel,
    onSelect: (LogLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        LogLevel.entries.forEach { level ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
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
private fun TerminalSurface(
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background),
    ) {
        content()
    }
}

@Composable
private fun EmptyState(isPaused: Boolean) {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier.fillMaxSize().padding(spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                if (isPaused) R.string.logs_empty_paused else R.string.logs_empty_idle
            ),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogList(entries: List<LogEntry>, isPaused: Boolean) {
    val listState = rememberLazyListState()
    val lastIndex = entries.lastIndex

    LaunchedEffect(entries.size, isPaused) {
        if (!isPaused && lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.medium,
            vertical = MaterialTheme.spacing.medium,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries.size, key = { it }) { index ->
            LogRow(entries[index])
        }
    }
}

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
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = entry.message,
                    style = monoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun LevelChip(level: LogLevel) {
    val color = level.accentColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = level.shortLabel(),
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

private fun LogLevel.shortLabel(): String = when (this) {
    LogLevel.DEBUG -> "DBG"
    LogLevel.INFO -> "INF"
    LogLevel.WARN -> "WRN"
    LogLevel.ERROR -> "ERR"
}

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(tint),
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(tint),
        )
    }
}

private val TIMESTAMP_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
