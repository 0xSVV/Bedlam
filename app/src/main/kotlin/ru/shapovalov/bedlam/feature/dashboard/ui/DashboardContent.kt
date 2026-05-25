package ru.shapovalov.bedlam.feature.dashboard.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.R
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
                    modifier = Modifier.size(SmallIconSize),
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

@Composable
private fun DashboardStore.ErrorReason.resolve(): String = when (this) {
    DashboardStore.ErrorReason.NoActiveProfile -> stringResource(R.string.dashboard_error_no_profile)
    DashboardStore.ErrorReason.ClipboardEmpty -> stringResource(R.string.dashboard_error_clipboard_empty)
    is DashboardStore.ErrorReason.ImportFailed ->
        cause ?: stringResource(R.string.dashboard_error_import_failed)
}

private val SmallIconSize = 20.dp
