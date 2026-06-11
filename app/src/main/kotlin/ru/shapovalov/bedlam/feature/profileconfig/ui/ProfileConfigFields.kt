package ru.shapovalov.bedlam.feature.profileconfig.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
        modifier = Modifier.fillMaxWidth(),
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
        caution = caution,
        editMode = editMode,
        showDivider = showDivider,
    ) {
        AnimatedFieldContent(editMode = editMode) { isEditing ->
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
        caution = caution,
        editMode = editMode,
        showDivider = showDivider,
    ) {
        AnimatedFieldContent(editMode = editMode) { isEditing ->
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SwitchRow(
    label: String,
    value: Boolean,
    editMode: Boolean,
    onChange: (Boolean) -> Unit,
    caution: String? = null,
    showDivider: Boolean = true,
) {
    val motion = MaterialTheme.motionScheme
    val spacing = MaterialTheme.spacing
    FieldRowFrame(
        label = label,
        caution = caution,
        editMode = editMode,
        showDivider = showDivider,
        labelTrailing = {
            AnimatedVisibility(
                visible = editMode,
                enter = fadeIn(motion.defaultEffectsSpec()) +
                        expandVertically(motion.fastSpatialSpec()) +
                        scaleIn(motion.fastSpatialSpec()),
                exit = fadeOut(motion.defaultEffectsSpec()) +
                        shrinkVertically(motion.fastSpatialSpec()) +
                        scaleOut(motion.fastSpatialSpec()),
            ) {
                Switch(checked = value, onCheckedChange = onChange)
            }
        },
    ) {
        AnimatedVisibility(
            visible = !editMode,
            enter = fadeIn(motion.defaultEffectsSpec()) +
                    expandVertically(motion.fastSpatialSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()) +
                    shrinkVertically(motion.fastSpatialSpec()),
        ) {
            ReadOnlyValue(
                value = value.toString(),
                modifier = Modifier.padding(top = spacing.small),
            )
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
    caution: String?,
    editMode: Boolean,
    showDivider: Boolean,
    labelTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
    ) {
        if (labelTrailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel(label, modifier = Modifier.weight(1f))
                labelTrailing()
            }
        } else {
            FieldLabel(label)
        }
        content()
        AnimatedHint(hint = caution, visible = editMode && !caution.isNullOrBlank())
        AnimatedDivider(visible = showDivider && !editMode)
    }
}

@Composable
private fun FieldLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedHint(hint: String?, visible: Boolean) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.fastSpatialSpec()),
        exit = fadeOut(motion.defaultEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
    ) {
        HintLabel(hint.orEmpty())
    }
}

@Composable
private fun HintLabel(hint: String) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.small),
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
private fun ReadOnlyValue(value: String, modifier: Modifier = Modifier) {
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
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedDivider(visible: Boolean) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.fastSpatialSpec()),
        exit = fadeOut(motion.defaultEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(top = MaterialTheme.spacing.small),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.16f),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedFieldContent(
    editMode: Boolean,
    content: @Composable (Boolean) -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = editMode,
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()))
                .using(SizeTransform(clip = false) { _, _ -> motion.fastSpatialSpec() })
        },
        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
        label = "profile-field-content",
    ) { isEditing ->
        content(isEditing)
    }
}
