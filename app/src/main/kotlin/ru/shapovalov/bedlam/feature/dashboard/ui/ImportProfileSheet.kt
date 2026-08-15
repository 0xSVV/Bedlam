package ru.shapovalov.bedlam.feature.dashboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.model.detectProfileImportFormat
import ru.shapovalov.bedlam.feature.dashboard.presentation.DashboardStore
import ru.shapovalov.bedlam.ui.theme.spacing

private val ImportProgressSize = 18.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ImportProfileSheet(
    seed: DashboardStore.ImportSheetSeed,
    isImporting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (ProfileImportFormat, String, String) -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current

    var format by rememberSaveable(seed) { mutableStateOf(seed.format) }
    var text by rememberSaveable(seed) { mutableStateOf(seed.text) }
    var name by rememberSaveable(seed) { mutableStateOf("") }
    var attemptedText by rememberSaveable(seed) { mutableStateOf<String?>(null) }
    val shownError = error
        ?.takeIf { attemptedText == text }
        ?.ifEmpty { stringResource(R.string.import_error_failed) }
    val formats = remember { ProfileImportFormat.entries.toList() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.large)
                .padding(bottom = spacing.large)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Text(
                text = stringResource(R.string.import_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                formats.forEachIndexed { index, entry ->
                    ToggleButton(
                        checked = format == entry,
                        onCheckedChange = { if (it) format = entry },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            formats.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) {
                        Text(
                            text = entry.label(),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(format.fieldLabel()) },
                placeholder = { Text(format.placeholder()) },
                singleLine = format == ProfileImportFormat.Link,
                minLines = if (format == ProfileImportFormat.Json) 4 else 1,
                maxLines = if (format == ProfileImportFormat.Json) 8 else 1,
                isError = shownError != null,
                supportingText = shownError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (format == ProfileImportFormat.Link) {
                        KeyboardType.Uri
                    } else {
                        KeyboardType.Text
                    },
                    autoCorrectEnabled = false,
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val pasted = clipboard.getClipEntry()
                                    ?.clipData
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    ?.trim()
                                    .orEmpty()
                                if (pasted.isNotEmpty()) {
                                    text = pasted
                                    format = detectProfileImportFormat(pasted)
                                }
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_paste),
                            contentDescription = stringResource(R.string.import_action_paste),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.import_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    },
                    enabled = !isImporting,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        attemptedText = text
                        onImport(format, text, name)
                    },
                    enabled = text.isNotBlank() && !isImporting,
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ImportProgressSize),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.import_action_import))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileImportFormat.label(): String = when (this) {
    ProfileImportFormat.Link -> stringResource(R.string.import_format_link)
    ProfileImportFormat.Json -> stringResource(R.string.import_format_json)
}

@Composable
private fun ProfileImportFormat.fieldLabel(): String = when (this) {
    ProfileImportFormat.Link -> stringResource(R.string.import_field_link)
    ProfileImportFormat.Json -> stringResource(R.string.import_field_json)
}

@Composable
private fun ProfileImportFormat.placeholder(): String = when (this) {
    ProfileImportFormat.Link -> stringResource(R.string.import_placeholder_link)
    ProfileImportFormat.Json -> stringResource(R.string.import_placeholder_json)
}
