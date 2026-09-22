package ru.shapovalov.bedlam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.ui.theme.spacing

@Composable
fun NativeLoadErrorContent(
    error: Throwable,
    supportedAbis: List<String>,
    onOpenReleases: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        ) {
            Text(
                text = stringResource(R.string.native_load_error_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.native_load_error_body, supportedAbis.firstOrNull().orEmpty()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.native_load_error_abis, supportedAbis.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = generateSequence(error) { it.cause }.last().toString(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenReleases) {
                Text(stringResource(R.string.native_load_error_open_releases))
            }
        }
    }
}
