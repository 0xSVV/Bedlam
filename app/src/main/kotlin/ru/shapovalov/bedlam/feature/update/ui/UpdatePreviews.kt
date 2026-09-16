package ru.shapovalov.bedlam.feature.update.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.update.presentation.UpdateStore
import ru.shapovalov.bedlam.ui.theme.BedlamTheme
import ru.shapovalov.bedlam.ui.theme.spacing

@Preview(name = "Light", showBackground = true, widthDp = 360)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class UpdatePreviews

private const val PreviewReleaseNotes = """Bedlam 1.6.0 fixes Android 10 and 11 and updates the Hysteria core to 2.12.3.

**DNS**

- The presets use `one.one.one.one` and `dns.google` instead of numeric addresses.
- A DNS connection that stops answering is replaced sooner.

**Other changes**

- **Raise `quic.maxIdleTimeout`** on your server to keep a sleeping phone connected.
- The [1.5.3 notes](release-notes/1.5.3.md) explain the earlier timer change."""

@Composable
private fun UpdatePreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.padding(MaterialTheme.spacing.large)) {
                content()
            }
        }
    }
}

@UpdatePreviews
@Composable
private fun ReleaseNotesCardPreview() {
    UpdatePreview {
        ReleaseNotesCard(
            notes = PreviewReleaseNotes,
            onOpenUrl = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
        )
    }
}

@UpdatePreviews
@Composable
private fun ReleaseNotesCardEmptyPreview() {
    UpdatePreview {
        ReleaseNotesCard(notes = "", onOpenUrl = {}, modifier = Modifier.fillMaxWidth())
    }
}

@UpdatePreviews
@Composable
private fun IdleActionsPreview() {
    UpdatePreview { IdleActions(onInstall = {}, onSkip = {}) }
}

@UpdatePreviews
@Composable
private fun DownloadProgressPreview() {
    UpdatePreview {
        DownloadProgress(
            phase = UpdateStore.State.Phase.Downloading(
                downloadedBytes = 7_400_000L,
                totalBytes = 21_400_000L,
            ),
        )
    }
}

@UpdatePreviews
@Composable
private fun InstallingIndicatorPreview() {
    UpdatePreview { InstallingIndicator() }
}

@UpdatePreviews
@Composable
private fun FailedActionsPreview() {
    UpdatePreview {
        FailedActions(message = "connection reset by peer", onRetry = {}, onSkip = {})
    }
}

@UpdatePreviews
@Composable
private fun InstallPermissionActionsPreview() {
    UpdatePreview { InstallPermissionActions(onRetry = {}, onSkip = {}) }
}

@UpdatePreviews
@Composable
private fun BlockedActionsPreview() {
    UpdatePreview {
        BlockedActions(
            message = stringResource(R.string.update_error_signature),
            onSkip = {},
        )
    }
}
