package ru.shapovalov.bedlam.feature.settings.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.predictiveBackAnimation
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.power.domain.PowerReliabilityRules
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.vpn.tile.BedlamTileService
import ru.shapovalov.bedlam.feature.appselection.ui.AppSelectionContent
import ru.shapovalov.bedlam.feature.routing.ui.RoutingContent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent
import ru.shapovalov.bedlam.feature.settings.presentation.SettingsComponent.Child
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalDecomposeApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsContent(component: SettingsComponent, modifier: Modifier = Modifier) {
    val state by component.state.collectAsState()

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
                onOpenBatteryReliability = component::onOpenBatteryReliability,
                quickSettingsTileAdded = state.quickSettingsTileAdded,
                onQuickSettingsTileAdded = component::onSetQuickSettingsTileAdded,
                reliabilitySnapshot = state.reliabilitySnapshot,
                confirmedReliabilityFingerprint = state.confirmedReliabilityFingerprint,
            )

            is Child.AppSelection -> AppSelectionContent(child.component)
            is Child.Routing -> RoutingContent(child.component)
            Child.BatteryReliability -> BatteryReliabilityContent(
                snapshot = state.reliabilitySnapshot,
                confirmedFingerprint = state.confirmedReliabilityFingerprint,
                onMarkConfirmed = component::onMarkReliabilityConfirmed,
                onBack = component::onBack,
            )
        }
    }
}

@Composable
private fun SettingsRoot(
    onOpenAppSelection: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenBatteryReliability: () -> Unit,
    quickSettingsTileAdded: Boolean,
    onQuickSettingsTileAdded: (Boolean) -> Unit,
    reliabilitySnapshot: PowerReliabilitySnapshot,
    confirmedReliabilityFingerprint: String?,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    val needsReliabilityAttention = PowerReliabilityRules.needsAttention(
        snapshot = reliabilitySnapshot,
        confirmedFingerprint = confirmedReliabilityFingerprint,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = spacing.small),
    ) {
        SettingsRow(
            title = stringResource(R.string.settings_apps_title),
            subtitle = stringResource(R.string.settings_apps_subtitle),
            onClick = onOpenAppSelection,
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_routing_title),
            subtitle = stringResource(R.string.settings_routing_subtitle),
            onClick = onOpenRouting,
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.settings_reliability_title),
            subtitle = stringResource(
                if (needsReliabilityAttention) {
                    R.string.settings_reliability_subtitle_action
                } else {
                    R.string.settings_reliability_subtitle_ok
                },
                reliabilitySnapshot.vendor.displayName,
            ),
            subtitleEmphasized = needsReliabilityAttention,
            onClick = onOpenBatteryReliability,
        )
        if (!quickSettingsTileAdded) {
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_quick_tile_title),
                subtitle = stringResource(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        R.string.settings_quick_tile_subtitle_disabled
                    } else {
                        R.string.settings_quick_tile_subtitle_manual
                    },
                ),
                subtitleEmphasized = true,
                showNavigationIcon = false,
                onClick = { requestQuickSettingsTile(context, onQuickSettingsTileAdded) },
            )
        }
    }
}

private fun requestQuickSettingsTile(context: Context, onAddedChanged: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = context.getSystemService(StatusBarManager::class.java) ?: return
        manager.requestAddTileService(
            ComponentName(context, BedlamTileService::class.java),
            context.getString(R.string.qs_tile_label),
            Icon.createWithResource(context, R.drawable.ic_qs_tunnel),
            context.mainExecutor,
        ) { result ->
            val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            if (added) {
                onAddedChanged(true)
            }
        }
    } else {
        Toast.makeText(
            context,
            R.string.settings_quick_tile_manual_hint,
            Toast.LENGTH_LONG,
        ).show()
        val intent = Intent(ACTION_QUICK_SETTINGS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
    }
}

private const val ACTION_QUICK_SETTINGS_SETTINGS = "android.settings.QUICK_SETTINGS_SETTINGS"

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun SettingsRow(
    title: String,
    subtitle: String,
    subtitleEmphasized: Boolean = false,
    showNavigationIcon: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsItemMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.large, vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            Spacer(Modifier.size(spacing.xSmall))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (subtitleEmphasized) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (showNavigationIcon) {
            Spacer(Modifier.width(spacing.small))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.large),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private val SettingsItemMinHeight = 84.dp
