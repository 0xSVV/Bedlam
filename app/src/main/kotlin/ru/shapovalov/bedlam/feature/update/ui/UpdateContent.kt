package ru.shapovalov.bedlam.feature.update.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.util.formatBytes
import ru.shapovalov.bedlam.feature.update.presentation.UpdateComponent
import ru.shapovalov.bedlam.feature.update.presentation.UpdateStore
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateContent(component: UpdateComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val spacing = MaterialTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(spacing.xLarge))
        Box(
            modifier = Modifier
                .size(UpdateBadgeSize)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(UpdateBadgeIconSize),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(spacing.large))
        Text(
            text = stringResource(R.string.update_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.xSmall))
        Text(
            text = stringResource(
                R.string.update_version_change,
                state.currentVersion,
                state.update.versionName,
            ),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(spacing.large))
        ReleaseNotesCard(
            notes = state.update.releaseNotes,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(Modifier.height(spacing.large))
        AnimatedContent(
            targetState = state.phase,
            contentKey = { it::class },
            label = "update-phase",
            modifier = Modifier.fillMaxWidth(),
        ) { phase ->
            when (phase) {
                UpdateStore.State.Phase.Idle -> IdleActions(
                    onInstall = component::onInstall,
                    onSkip = component::onSkip,
                )

                is UpdateStore.State.Phase.Downloading -> DownloadProgress(phase)

                UpdateStore.State.Phase.Installing -> InstallingIndicator()

                is UpdateStore.State.Phase.Failed -> FailedActions(
                    message = phase.message,
                    onRetry = component::onInstall,
                    onSkip = component::onSkip,
                )
            }
        }
        Spacer(Modifier.height(spacing.large))
    }
}

@Composable
private fun ReleaseNotesCard(notes: String, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = modifier, shape = MaterialTheme.shapes.extraLarge) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(spacing.large),
        ) {
            Text(
                text = stringResource(R.string.update_whats_new),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Spacer(Modifier.height(spacing.small))
            Text(
                text = notes.ifBlank { stringResource(R.string.update_notes_empty) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IdleActions(onInstall: () -> Unit, onSkip: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_action_install))
        }
        Spacer(Modifier.height(spacing.xSmall))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_action_skip))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadProgress(phase: UpdateStore.State.Phase.Downloading) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LinearWavyProgressIndicator(
            progress = {
                if (phase.totalBytes > 0) {
                    (phase.downloadedBytes.toFloat() / phase.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(spacing.medium))
        Text(
            text = stringResource(
                R.string.update_downloading,
                context.formatBytes(phase.downloadedBytes),
                context.formatBytes(phase.totalBytes),
            ),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallingIndicator() {
    val spacing = MaterialTheme.spacing
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LoadingIndicator(modifier = Modifier.size(UpdateLoadingIndicatorSize))
        Spacer(Modifier.height(spacing.xSmall))
        Text(
            text = stringResource(R.string.update_installing),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedActions(message: String, onRetry: () -> Unit, onSkip: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.update_failed, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(spacing.medium))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_action_retry))
        }
        Spacer(Modifier.height(spacing.xSmall))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_action_skip))
        }
    }
}

private val UpdateBadgeSize = 96.dp
private val UpdateBadgeIconSize = 40.dp
private val UpdateLoadingIndicatorSize = 48.dp
