package ru.shapovalov.bedlam.feature.settings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.feature.appselection.ui.AppSelectionContent
import ru.shapovalov.bedlam.feature.routing.ui.RoutingContent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent.Child
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalDecomposeApi::class)
@Composable
fun SettingsContent(component: SettingsComponent, modifier: Modifier = Modifier) {
    Children(
        stack = component.childStack,
        modifier = modifier.fillMaxSize(),
        animation = predictiveBackAnimation(
            backHandler = component.backHandler,
            fallbackAnimation = stackAnimation(fade()),
            onBack = component::onBack,
        ),
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

@Composable
private fun SettingsRoot(
    onOpenAppSelection: () -> Unit,
    onOpenRouting: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
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
        BatteryOptimizationRow(
            modifier = Modifier.padding(horizontal = spacing.small, vertical = spacing.small),
        )
    }
}

@Composable
private fun BatteryOptimizationRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val powerManager = remember {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    var unrestricted by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsRow(
        title = stringResource(R.string.settings_battery_title),
        subtitle = stringResource(
            if (unrestricted) R.string.settings_battery_subtitle_unrestricted
            else R.string.settings_battery_subtitle_restricted,
        ),
        onClick = {
            if (!unrestricted) {
                @Suppress("BatteryLife")
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                )
                context.startActivity(intent)
            }
        },
        modifier = modifier,
    )
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
