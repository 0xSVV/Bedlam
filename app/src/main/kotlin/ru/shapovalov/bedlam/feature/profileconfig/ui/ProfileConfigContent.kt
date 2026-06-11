package ru.shapovalov.bedlam.feature.profileconfig.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.util.isRealmAddress
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigComponent
import ru.shapovalov.bedlam.feature.profileconfig.presentation.ProfileConfigStore
import ru.shapovalov.bedlam.ui.theme.spacing
import ru.shapovalov.hysteria.config.HysteriaConfig

private val SaveProgressSize = 18.dp
private val BottomFabPadding = 96.dp

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
        floatingActionButton = {
            val draft = state.draft
            if (draft != null &&
                !state.editMode &&
                !state.isLoading &&
                !state.notFound &&
                !state.isDeleting
            ) {
                ProfileActionsMenu(
                    onDelete = component::onRequestDelete,
                    onCopy = {
                        clipboardManager.setPrimaryClip(
                            ClipData.newPlainText(clipboardLabel, draft.toClipboardText())
                        )
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    onEdit = component::onEnterEditMode,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
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
private fun ProfileActionsMenu(
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val openLabel = stringResource(R.string.profile_config_action_menu_open_cd)
    val closeLabel = stringResource(R.string.profile_config_action_menu_close_cd)

    BackHandler(expanded) { expanded = false }

    FloatingActionButtonMenu(
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
                onDelete()
            },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.profile_config_action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onCopy()
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = null,
                )

            },
            text = { Text(stringResource(R.string.profile_config_action_copy)) },
        )
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                onEdit()
            },
            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
            text = { Text(stringResource(R.string.profile_config_action_edit)) },
        )
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
        Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
            ServerSection(draft, editMode, onDraftChanged)
            if (isRealmAddress(draft.server.address) || draft.realm != null) {
                RealmSection(draft, editMode, onDraftChanged)
            }
            TlsSection(draft, editMode, onDraftChanged)
            ObfuscationSection(draft, editMode, onDraftChanged)
            QuicSection(draft, editMode, onDraftChanged)
            CongestionSection(draft, editMode, onDraftChanged)
            BandwidthSection(draft, editMode, onDraftChanged)
            TransportSection(draft, editMode, onDraftChanged)
            BehaviorSection(draft, editMode, onDraftChanged)
        }
        Spacer(Modifier.height(spacing.medium))
        DocsFooter()
        Spacer(Modifier.height(BottomFabPadding))
    }
}

@Composable
private fun DocsFooter() {
    val spacing = MaterialTheme.spacing
    val uriHandler = LocalUriHandler.current
    val url = stringResource(R.string.profile_config_docs_url)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.small, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
    ) {
        Text(
            text = stringResource(R.string.profile_config_docs_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.profile_config_docs_link),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
            ),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { uriHandler.openUri(url) },
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
