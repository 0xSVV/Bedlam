package ru.shapovalov.bedlam.feature.routing.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.routing.data.RoutePresets
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutePreset
import ru.shapovalov.bedlam.ui.theme.spacing
import java.util.UUID

private enum class SourceKind { CIDR, ASN, DOMAIN }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AddSourceDialog(
    existingKeys: Set<String>,
    onDismiss: () -> Unit,
    onSave: (DirectRouteSource) -> Unit,
) {
    var kind by remember { mutableStateOf(SourceKind.CIDR) }
    var value by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val parsed: DirectRouteSource? = remember(kind, value, comment) {
        val v = value.trim()
        if (v.isEmpty()) null else when (kind) {
            SourceKind.CIDR -> Cidr.parseOrNull(v)?.let {
                DirectRouteSource.Cidr(UUID.randomUUID().toString(), it, comment, true, 0)
            }

            SourceKind.ASN -> v.removePrefix("AS").removePrefix("as").trim().toIntOrNull()?.let {
                DirectRouteSource.Asn(UUID.randomUUID().toString(), it, comment, true, 0)
            }

            SourceKind.DOMAIN -> if (v.contains('.') && !v.contains(' ')) {
                DirectRouteSource.Domain(UUID.randomUUID().toString(), v, comment, true, 0)
            } else null
        }
    }
    val isDuplicate = parsed?.dedupeKey()?.let { it in existingKeys } == true
    val kinds = remember { SourceKind.entries.toList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_sources_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    kinds.forEachIndexed { index, entry ->
                        ToggleButton(
                            checked = kind == entry,
                            onCheckedChange = { if (it) kind = entry },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { role = Role.RadioButton },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                kinds.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
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
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.routing_sources_add_value_label)) },
                    placeholder = { Text(kind.placeholder()) },
                    singleLine = true,
                    isError = (value.isNotEmpty() && parsed == null) || isDuplicate,
                    supportingText = when {
                        isDuplicate -> {
                            { Text(stringResource(R.string.routing_sources_add_duplicate)) }
                        }

                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(stringResource(R.string.routing_sources_add_comment_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && !isDuplicate,
                onClick = { parsed?.let(onSave) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

internal data class PresetStatus(val present: Int, val total: Int) {
    val isFullyAdded: Boolean get() = present == total
    val isPartial: Boolean get() = present in 1 until total
    val isNew: Boolean get() = present == 0
}

@Composable
internal fun PresetsDialog(
    statuses: Map<String, PresetStatus>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routing_presets_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                RoutePresets.ALL.forEach { preset ->
                    val status = statuses[preset.id] ?: PresetStatus(0, preset.asns.size)
                    PresetRow(preset = preset, status = status, onPick = { onPick(preset.id) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PresetRow(
    preset: RoutePreset,
    status: PresetStatus,
    onPick: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val enabled = !status.isFullyAdded
    val (dotColor, statusLabel) = when {
        status.isFullyAdded -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f) to
                stringResource(R.string.routing_preset_added)

        status.isPartial -> MaterialTheme.colorScheme.tertiary to
                stringResource(R.string.routing_preset_partial, status.present, status.total)

        else -> MaterialTheme.colorScheme.primary to
                stringResource(R.string.routing_preset_asn_count, status.total)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .let { if (enabled) it.clickable(onClick = onPick) else it }
            .padding(spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.isFullyAdded) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(PresetCheckIconSize),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(PresetDotSize)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.width(spacing.medium))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.name,
                style = if (enabled) MaterialTheme.typography.titleSmallEmphasized
                else MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xSmall))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = dotColor,
            )
        }
    }
}

@Composable
private fun SourceKind.label(): String = stringResource(
    when (this) {
        SourceKind.CIDR -> R.string.routing_kind_cidr
        SourceKind.ASN -> R.string.routing_kind_asn
        SourceKind.DOMAIN -> R.string.routing_kind_domain
    }
)

private fun SourceKind.placeholder(): String = when (this) {
    SourceKind.CIDR -> "10.0.0.0/8"
    SourceKind.ASN -> "AS13335"
    SourceKind.DOMAIN -> "example.com"
}

private val PresetDotSize = 8.dp
private val PresetCheckIconSize = 16.dp
