package ru.shapovalov.bedlam.feature.profileconfig.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.util.isRealmAddress
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponent
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigStore
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.config.HysteriaConfig

private val SaveProgressSize = 18.dp
private val BottomToolbarPadding = 96.dp
private val SnackbarToolbarClearance = 80.dp

private val ProfileConfigStore.State.toolbarVisible: Boolean
    get() = draft != null && !editMode && !isLoading && !notFound && !isDeleting

private val ClipboardJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileConfigContent(component: ProfileConfigComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(ClipboardManager::class.java)
    }
    val clipboardLabel = stringResource(R.string.profile_config_clip_label)
    val copiedMessage = stringResource(R.string.profile_config_copy_success)

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
        snackbarHost = {
            val toolbarVisible = state.toolbarVisible
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = if (toolbarVisible) SnackbarToolbarClearance else 0.dp,
                ),
            ) { Snackbar(snackbarData = it) }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
            ProfileActionsToolbar(
                visible = state.toolbarVisible,
                onDelete = component::onRequestDelete,
                onCopy = {
                    val current = state.draft ?: return@ProfileActionsToolbar
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText(clipboardLabel, current.toClipboardText())
                    )
                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                },
                onEdit = component::onEnterEditMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -FloatingToolbarDefaults.ScreenOffset),
            )
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

    AnimatedContent(
        targetState = state.editMode,
        transitionSpec = {
            fadeIn(tween(durationMillis = 180, delayMillis = 60)) togetherWith
                    fadeOut(tween(durationMillis = 90))
        },
        label = "profile-config-top-actions",
    ) { editMode ->
        if (!editMode) {
            Spacer(Modifier.size(0.dp))
        } else {
            Row {
                TextButton(onClick = component::onDiscardChanges, enabled = !state.isSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = component::onSave,
                    enabled = !state.isSaving && state.isDirty,
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(SaveProgressSize),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileActionsToolbar(
    visible: Boolean,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(motion.defaultSpatialSpec()) { it * 2 } +
                fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.defaultSpatialSpec()) { it * 2 } +
                fadeOut(motion.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.profile_config_action_edit),
                    )
                }
            },
        ) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.profile_config_action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = stringResource(R.string.profile_config_action_copy),
                )
            }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.large,
            end = spacing.large,
            top = spacing.medium,
            bottom = BottomToolbarPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        item(key = "server") { ServerSection(draft, editMode, onDraftChanged) }
        if (isRealmAddress(draft.server.address) || draft.realm != null) {
            item(key = "realm") { RealmSection(draft, editMode, onDraftChanged) }
        }
        item(key = "tls") { TlsSection(draft, editMode, onDraftChanged) }
        item(key = "obfuscation") { ObfuscationSection(draft, editMode, onDraftChanged) }
        item(key = "quic") { QuicSection(draft, editMode, onDraftChanged) }
        item(key = "congestion") { CongestionSection(draft, editMode, onDraftChanged) }
        item(key = "bandwidth") { BandwidthSection(draft, editMode, onDraftChanged) }
        item(key = "transport") { TransportSection(draft, editMode, onDraftChanged) }
        item(key = "behavior") { BehaviorSection(draft, editMode, onDraftChanged) }
        item(key = "docs") { DocsFooter() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DocsFooter() {
    val spacing = MaterialTheme.spacing
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.profile_config_docs_url)
    ElevatedButton(
        onClick = { uriHandler.openUri(url) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.elevatedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(spacing.large),
    ) {
        Text(
            text = stringResource(R.string.profile_config_docs_link),
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
        )
    }
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
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.large),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.profile_config_not_found),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun HysteriaConfig.toClipboardText(): String =
    ClipboardJson.encodeToString(this)
