package ru.shapovalov.bedlam.feature.profileconfig.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.ui.theme.spacing
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(vertical = spacing.small)) {
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
) {
    FieldRowFrame(label = label, caution = caution.takeIf { editMode }) {
        if (editMode) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 6,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ReadOnlyValue(value = value)
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
) {
    NumericFieldRow(
        label = label,
        text = value.toString(),
        editMode = editMode,
        caution = caution,
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
) {
    NumericFieldRow(
        label = label,
        text = value.toString(),
        editMode = editMode,
        caution = caution,
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
    parse: (String) -> T?,
    onChange: (T) -> Unit,
) {
    FieldRowFrame(label = label, caution = caution.takeIf { editMode }) {
        if (editMode) {
            var local by remember(text) { mutableStateOf(text) }
            OutlinedTextField(
                value = local,
                onValueChange = { entry ->
                    local = entry
                    val source = entry.ifEmpty { "0" }
                    parse(source)?.let(onChange)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ReadOnlyValue(value = text)
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
) {
    val spacing = MaterialTheme.spacing
    if (!editMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.large, vertical = spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = value, onCheckedChange = onChange)
        }
        CautionLabel(caution)
    }
}

@Composable
private fun FieldRowFrame(
    label: String,
    caution: String?,
    content: @Composable () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        CautionLabel(caution)
    }
}

@Composable
private fun CautionLabel(caution: String?) {
    if (caution.isNullOrBlank()) return
    Text(
        text = caution,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ReadOnlyValue(value: String) {
    Text(
        text = value.takeIf { it.isNotBlank() } ?: stringResource(R.string.profile_config_field_empty),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}
