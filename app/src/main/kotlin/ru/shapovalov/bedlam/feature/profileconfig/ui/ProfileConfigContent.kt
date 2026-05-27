package ru.shapovalov.bedlam.feature.profileconfig.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponent
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigStore
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.config.HysteriaConfig

private val SaveProgressSize = 18.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileConfigContent(component: ProfileConfigComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler { component.onBackPressed() }

    val saveErrorMessage = state.saveError?.let {
        stringResource(R.string.profile_config_save_error, it)
    }
    LaunchedEffect(saveErrorMessage) {
        val msg = saveErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        component.onDismissError()
    }

    LaunchedEffect(state.notFound) {
        if (state.notFound) component.onBackPressed()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.original?.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.profile_config_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBackPressed) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = { TopActions(state, component) },
            )
        },
        floatingActionButton = {
            val showFab = state.draft != null && !state.editMode && !state.isLoading && !state.notFound
            if (showFab) {
                FloatingActionButton(onClick = component::onEnterEditMode) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.profile_config_action_edit),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val draft = state.draft
            when {
                state.isLoading -> CenteredSpinner()
                state.notFound -> NotFoundMessage()
                draft != null -> ConfigBody(
                    draft = draft,
                    editMode = state.editMode,
                    onDraftChanged = component::onDraftChanged,
                )
            }
        }
    }

    if (state.pendingDeleteConfirmation) {
        DeleteConfirmationDialog(
            onConfirm = component::onConfirmDelete,
            onDismiss = component::onCancelDelete,
        )
    }
}

@Composable
private fun TopActions(
    state: ProfileConfigStore.State,
    component: ProfileConfigComponent,
) {
    val ready = state.draft != null && !state.isLoading && !state.notFound
    if (!ready) return

    if (!state.editMode) {
        IconButton(
            onClick = component::onRequestDelete,
            enabled = !state.isDeleting,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.profile_config_action_delete),
            )
        }
        return
    }

    TextButton(onClick = component::onDiscardChanges, enabled = !state.isSaving) {
        Text(stringResource(R.string.action_cancel))
    }
    TextButton(
        onClick = component::onSave,
        enabled = !state.isSaving && state.isDirty,
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(SaveProgressSize), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_config_delete_title)) },
        text = { Text(stringResource(R.string.profile_config_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.profile_config_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ConfigBody(
    draft: HysteriaConfig,
    editMode: Boolean,
    onDraftChanged: (HysteriaConfig) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.large),
    ) {
        Spacer(Modifier.height(spacing.medium))
        DocsLink()
        Spacer(Modifier.height(spacing.medium))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            ServerSection(draft, editMode, onDraftChanged)
            TlsSection(draft, editMode, onDraftChanged)
            ObfuscationSection(draft, editMode, onDraftChanged)
            QuicSection(draft, editMode, onDraftChanged)
            CongestionSection(draft, editMode, onDraftChanged)
            BandwidthSection(draft, editMode, onDraftChanged)
            TransportSection(draft, editMode, onDraftChanged)
            BehaviorSection(draft, editMode, onDraftChanged)
        }
        Spacer(Modifier.height(spacing.xLarge))
    }
}

@Composable
private fun DocsLink() {
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.profile_config_docs_url)
    ElevatedAssistChip(
        onClick = { uriHandler.openUri(url) },
        label = {
            Text(
                text = stringResource(R.string.profile_config_docs_link),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator()
    }
}

@Composable
private fun NotFoundMessage() {
    val spacing = MaterialTheme.spacing
    Box(
        modifier = Modifier.fillMaxSize().padding(spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.profile_config_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
