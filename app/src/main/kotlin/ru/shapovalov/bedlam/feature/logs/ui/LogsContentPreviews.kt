package ru.shapovalov.bedlam.feature.logs.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.api.HysteriaClient.LogEntry
import ru.shapovalov.hysteria.api.HysteriaClient.LogLevel

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 320)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 320,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class LogsPreviews

private const val PreviewStartMillis = 1_767_225_600_000L

private val previewEntries = listOf(
    Triple(LogLevel.DEBUG, "tun", "stack ready, mtu 1280"),
    Triple(LogLevel.INFO, "session", "connected to vpn.example.com:443"),
    Triple(LogLevel.INFO, "dns", "upstream https://dns.example/dns-query"),
    Triple(LogLevel.WARN, "reconnect", "no traffic for 30s, probing the tunnel"),
    Triple(LogLevel.ERROR, "session", "authentication error, HTTP status code: 401"),
    Triple(LogLevel.INFO, "", "a line without a source that wraps onto a second line in the list"),
).mapIndexed { index, (level, source, message) ->
    LogEntry(
        level = level,
        source = source,
        message = message,
        timestampMillis = PreviewStartMillis + index * 1_250L,
        seq = index.toLong(),
    )
}

@Composable
private fun LogsPreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@LogsPreviews
@Composable
private fun LevelFilterRowPreview() {
    LogsPreview {
        LevelFilterRow(
            selected = LogLevel.WARN,
            onSelect = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.large,
                    vertical = MaterialTheme.spacing.small,
                ),
        )
    }
}

@LogsPreviews
@Composable
private fun LogsWaitingPreview() {
    LogsPreview { EmptyState(text = stringResource(R.string.logs_empty_idle)) }
}

@LogsPreviews
@Composable
private fun LogsPausedPreview() {
    LogsPreview { EmptyState(text = stringResource(R.string.logs_empty_paused)) }
}

@LogsPreviews
@Composable
private fun LogsFilteredPreview() {
    LogsPreview {
        EmptyState(
            text = stringResource(
                R.string.logs_empty_filtered,
                stringResource(R.string.logs_level_error),
            ),
        )
    }
}

@LogsPreviews
@Composable
private fun LogListPreview() {
    LogsPreview { LogList(entries = previewEntries, droppedCount = 0L) }
}

@LogsPreviews
@Composable
private fun LogListDroppedPreview() {
    LogsPreview { LogList(entries = previewEntries, droppedCount = 1_024L) }
}
