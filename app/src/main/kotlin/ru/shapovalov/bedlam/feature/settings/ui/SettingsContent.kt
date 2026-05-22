package ru.shapovalov.bedlam.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.slide
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.appselection.ui.AppSelectionContent
import ru.shapovalov.bedlam.feature.routing.ui.RoutingContent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent.Child
import ru.shapovalov.bedlam.ui.theme.spacing

@Composable
fun SettingsContent(component: SettingsComponent, modifier: Modifier = Modifier) {
    Children(
        stack = component.childStack,
        modifier = modifier.fillMaxSize(),
        animation = stackAnimation(slide()),
    ) { created ->
        when (val child = created.instance) {
            Child.Root -> SettingsRoot(
                onOpenAppSelection = component::onOpenAppSelection,
                onOpenRouting = component::onOpenRouting,
            )
            is Child.AppSelection -> AppSelectionContent(child.component)
            is Child.Routing -> RoutingContent(child.component)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(
    onOpenAppSelection: () -> Unit,
    onOpenRouting: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SettingsRow(
                title = stringResource(R.string.settings_apps_title),
                subtitle = stringResource(R.string.settings_apps_subtitle),
                onClick = onOpenAppSelection,
                modifier = Modifier.padding(horizontal = spacing.small, vertical = spacing.small),
            )
            SettingsRow(
                title = stringResource(R.string.settings_routing_title),
                subtitle = stringResource(R.string.settings_routing_subtitle),
                onClick = onOpenRouting,
                modifier = Modifier.padding(horizontal = spacing.small, vertical = spacing.small),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.size(spacing.xSmall))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(spacing.small))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
