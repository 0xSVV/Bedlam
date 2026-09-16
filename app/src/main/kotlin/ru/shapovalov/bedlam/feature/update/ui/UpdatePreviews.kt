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

private const val PreviewReleaseNotes = """Changes in this version

- A short change
- A longer change whose description wraps onto a second line of the card
- Another short change"""

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
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )
    }
}

@UpdatePreviews
@Composable
private fun ReleaseNotesCardEmptyPreview() {
    UpdatePreview {
        ReleaseNotesCard(notes = "", modifier = Modifier.fillMaxWidth())
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
