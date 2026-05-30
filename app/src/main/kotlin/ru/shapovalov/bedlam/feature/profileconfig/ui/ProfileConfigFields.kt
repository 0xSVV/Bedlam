package ru.shapovalov.bedlam.feature.profileconfig.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 220)),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.small),
            )
            Column(content = content)
        }
    }
}

@Composable
internal fun TextFieldRow(
    label: String,
    value: String,
    editMode: Boolean,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    caution: String? = null,
    showDivider: Boolean = true,
) {
    FieldRowFrame(
        label = label,
        hint = caution.takeIf { editMode },
        showDivider = showDivider,
    ) {
        AnimatedFieldContent(editMode = editMode, label = label) { isEditing ->
            if (isEditing) {
                ConfigTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = singleLine,
                )
            } else {
                ReadOnlyValue(value = value)
            }
        }
    }
}

@Composable
internal fun IntFieldRow(
    label: String,
    value: Int,
    editMode: Boolean,
    onChange: (Int) -> Unit,
    caution: String? = null,
    showDivider: Boolean = true,
) {
    NumericFieldRow(
        label = label,
        text = value.toString(),
        editMode = editMode,
        caution = caution,
        showDivider = showDivider,
        parse = { it.toIntOrNull() },
        onChange = onChange,
    )
}

@Composable
internal fun LongFieldRow(
    label: String,
    value: Long,
    editMode: Boolean,
    onChange: (Long) -> Unit,
    caution: String? = null,
    showDivider: Boolean = true,
) {
    NumericFieldRow(
        label = label,
        text = value.toString(),
        editMode = editMode,
        caution = caution,
        showDivider = showDivider,
        parse = { it.toLongOrNull() },
        onChange = onChange,
    )
}

@Composable
private fun <T> NumericFieldRow(
    label: String,
    text: String,
    editMode: Boolean,
    caution: String?,
    showDivider: Boolean,
    parse: (String) -> T?,
    onChange: (T) -> Unit,
) {
    FieldRowFrame(
        label = label,
        hint = caution.takeIf { editMode },
        showDivider = showDivider,
    ) {
        AnimatedFieldContent(editMode = editMode, label = label) { isEditing ->
            if (isEditing) {
                var local by remember(text) { mutableStateOf(text) }
                ConfigTextField(
                    value = local,
                    onValueChange = { entry ->
                        local = entry
                        val source = entry.ifEmpty { "0" }
                        parse(source)?.let(onChange)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            } else {
                ReadOnlyValue(value = text)
            }
        }
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    value: Boolean,
    editMode: Boolean,
    onChange: (Boolean) -> Unit,
    caution: String? = null,
    showDivider: Boolean = true,
) {
    FieldRowFrame(
        label = label,
        hint = caution.takeIf { editMode },
        showDivider = showDivider,
    ) {
        AnimatedFieldContent(editMode = editMode, label = label) { isEditing ->
            if (isEditing) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Switch(
                        checked = value,
                        onCheckedChange = onChange,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            } else {
                ReadOnlyValue(value = value.toString())
            }
        }
    }
}

@Composable
private fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 6,
        keyboardOptions = keyboardOptions,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
        shape = MaterialTheme.shapes.large,
        placeholder = {
            Text(
                text = stringResource(R.string.profile_config_field_empty),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.0f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.0f),
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun FieldRowFrame(
    label: String,
    hint: String?,
    showDivider: Boolean,
    content: @Composable () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        FieldLabel(label)
        content()
        HintLabel(hint)
        if (showDivider) {
            FieldDivider()
        }
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HintLabel(hint: String?) {
    if (hint.isNullOrBlank()) return

    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.xSmall),
        horizontalArrangement = Arrangement.spacedBy(spacing.xSmall),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(R.string.profile_config_hint_prefix),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReadOnlyValue(value: String) {
    val displayValue = value.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.profile_config_field_empty)
    val isEmpty = value.isBlank()

    Text(
        text = displayValue,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = if (isEmpty) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FieldDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.16f),
    )
}

@Composable
private fun AnimatedFieldContent(
    editMode: Boolean,
    label: String,
    content: @Composable (Boolean) -> Unit,
) {
    AnimatedContent(
        targetState = editMode,
        transitionSpec = {
            fadeIn(tween(durationMillis = 180, delayMillis = 50)) togetherWith
                    fadeOut(tween(durationMillis = 90))
        },
        label = "profile-field-$label",
    ) { isEditing ->
        content(isEditing)
    }
}
