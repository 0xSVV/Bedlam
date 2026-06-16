package ru.shapovalov.bedlam.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.power.PowerSettingsLauncher
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket
import ru.shapovalov.bedlam.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BatteryReliabilityContent(
    snapshot: PowerReliabilitySnapshot,
    confirmedFingerprint: String?,
    onMarkConfirmed: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val confirmed = confirmedFingerprint == snapshot.buildFingerprint
    val spacing = MaterialTheme.spacing

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_reliability_title),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = spacing.large,
                vertical = spacing.medium,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            item(key = "hero") {
                ReliabilityHero(
                    snapshot = snapshot,
                    confirmed = confirmed,
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "checklist") {
                ChecklistCard(
                    snapshot = snapshot,
                    confirmed = confirmed,
                    onOpenBatteryOptimization = {
                        PowerSettingsLauncher.openBatteryOptimization(context)
                    },
                    onOpenAppInfo = { PowerSettingsLauncher.openAppInfo(context) },
                    onOpenNotifications = {
                        PowerSettingsLauncher.openNotificationSettings(context)
                    },
                    onOpenVpnSettings = { PowerSettingsLauncher.openVpnSettings(context) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "vendor") {
                VendorGuideCard(
                    snapshot = snapshot,
                    confirmed = confirmed,
                    onOpenAppInfo = { PowerSettingsLauncher.openAppInfo(context) },
                    onOpenVendorManager = {
                        PowerSettingsLauncher.openVendorPowerManager(context, snapshot.vendor)
                    },
                    onConfirm = {
                        onMarkConfirmed(snapshot.buildFingerprint)
                    },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "explain") {
                ExplanationCard(modifier = Modifier.animateItem())
            }

            item(key = "trailing-space") {
                Spacer(Modifier.height(spacing.large))
            }
        }
    }
}

@Composable
private fun ReliabilityHero(
    snapshot: PowerReliabilitySnapshot,
    confirmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val titleRes = when {
        confirmed && snapshot.vendor.needsManualBackgroundAccess ->
            R.string.settings_reliability_hero_confirmed_title

        snapshot.riskLevel == PowerRiskLevel.High ->
            R.string.settings_reliability_hero_high_title

        snapshot.riskLevel == PowerRiskLevel.Medium ->
            R.string.settings_reliability_hero_medium_title

        else -> R.string.settings_reliability_hero_low_title
    }
    val bodyRes = when {
        confirmed && snapshot.vendor.needsManualBackgroundAccess ->
            R.string.settings_reliability_hero_confirmed_body

        snapshot.riskLevel == PowerRiskLevel.High ->
            R.string.settings_reliability_hero_high_body

        snapshot.riskLevel == PowerRiskLevel.Medium ->
            R.string.settings_reliability_hero_medium_body

        else -> R.string.settings_reliability_hero_low_body
    }

    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.large)) {
            StatusPill(
                text = stringResource(
                    if (confirmed && snapshot.vendor.needsManualBackgroundAccess) {
                        R.string.settings_reliability_status_marked_done
                    } else {
                        snapshot.riskLevel.labelRes()
                    },
                ),
                tone = if (confirmed && snapshot.vendor.needsManualBackgroundAccess) {
                    CheckTone.Good
                } else {
                    when (snapshot.riskLevel) {
                        PowerRiskLevel.Low -> CheckTone.Good
                        PowerRiskLevel.Medium -> CheckTone.Info
                        PowerRiskLevel.High -> CheckTone.Warning
                    }
                },
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
            Text(
                text = stringResource(bodyRes, snapshot.vendor.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChecklistCard(
    snapshot: PowerReliabilitySnapshot,
    confirmed: Boolean,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column {
            SectionHeader(
                title = stringResource(R.string.settings_reliability_checklist_title),
                body = stringResource(R.string.settings_reliability_checklist_body),
            )
            HorizontalDivider()
            CheckRow(
                title = stringResource(R.string.settings_reliability_check_battery_title),
                body = stringResource(R.string.settings_reliability_check_battery_body),
                status = stringResource(
                    if (snapshot.batteryUnrestricted) {
                        R.string.settings_reliability_status_unrestricted
                    } else {
                        R.string.settings_reliability_status_needs_action
                    },
                ),
                tone = if (snapshot.batteryUnrestricted) CheckTone.Good else CheckTone.Warning,
                actionLabel = if (snapshot.batteryUnrestricted) null
                else stringResource(R.string.settings_reliability_action_allow),
                onAction = onOpenBatteryOptimization,
            )
            HorizontalDivider()
            CheckRow(
                title = stringResource(R.string.settings_reliability_check_vendor_title),
                body = stringResource(
                    if (snapshot.vendor.needsManualBackgroundAccess) {
                        R.string.settings_reliability_check_vendor_body_risky
                    } else {
                        R.string.settings_reliability_check_vendor_body_normal
                    },
                    snapshot.vendor.displayName,
                ),
                status = stringResource(
                    when {
                        !snapshot.vendor.needsManualBackgroundAccess ->
                            R.string.settings_reliability_status_not_needed

                        confirmed -> R.string.settings_reliability_status_marked_done
                        else -> R.string.settings_reliability_status_review
                    },
                ),
                tone = when {
                    !snapshot.vendor.needsManualBackgroundAccess || confirmed -> CheckTone.Good
                    else -> CheckTone.Warning
                },
                actionLabel = stringResource(R.string.settings_reliability_action_app_info),
                onAction = onOpenAppInfo,
            )
            HorizontalDivider()
            CheckRow(
                title = stringResource(R.string.settings_reliability_check_restricted_title),
                body = stringResource(
                    R.string.settings_reliability_check_restricted_body,
                    stringResource(snapshot.standbyBucket.labelRes()),
                ),
                status = stringResource(
                    if (snapshot.isBackgroundRestricted()) {
                        R.string.settings_reliability_status_restricted
                    } else {
                        R.string.settings_reliability_status_allowed
                    },
                ),
                tone = if (snapshot.isBackgroundRestricted()) {
                    CheckTone.Warning
                } else {
                    CheckTone.Good
                },
                actionLabel = if (snapshot.isBackgroundRestricted()) {
                    stringResource(R.string.settings_reliability_action_app_info)
                } else {
                    null
                },
                onAction = onOpenAppInfo,
            )
            HorizontalDivider()
            CheckRow(
                title = stringResource(R.string.settings_reliability_check_notifications_title),
                body = stringResource(R.string.settings_reliability_check_notifications_body),
                status = stringResource(
                    if (snapshot.notificationsAllowed) {
                        R.string.settings_reliability_status_allowed
                    } else {
                        R.string.settings_reliability_status_blocked
                    },
                ),
                tone = if (snapshot.notificationsAllowed) CheckTone.Good else CheckTone.Warning,
                actionLabel = if (snapshot.notificationsAllowed) null
                else stringResource(R.string.settings_reliability_action_notifications),
                onAction = onOpenNotifications,
            )
            HorizontalDivider()
            CheckRow(
                title = stringResource(R.string.settings_reliability_check_always_on_title),
                body = stringResource(R.string.settings_reliability_check_always_on_body),
                status = stringResource(snapshot.alwaysOnVpnState.labelRes()),
                tone = when (snapshot.alwaysOnVpnState) {
                    AlwaysOnVpnState.Enabled,
                    AlwaysOnVpnState.EnabledWithLockdown -> CheckTone.Good

                    AlwaysOnVpnState.OtherVpn -> CheckTone.Warning
                    AlwaysOnVpnState.Disabled,
                    AlwaysOnVpnState.Unknown -> CheckTone.Info
                },
                actionLabel = stringResource(R.string.settings_reliability_action_vpn_settings),
                onAction = onOpenVpnSettings,
            )
        }
    }
}

@Composable
private fun VendorGuideCard(
    snapshot: PowerReliabilitySnapshot,
    confirmed: Boolean,
    onOpenAppInfo: () -> Unit,
    onOpenVendorManager: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val guide = snapshot.vendor.guide()
    val steps = stringArrayResource(guide.stepsArrayRes)
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.large)) {
            Text(
                text = stringResource(guide.titleRes, snapshot.vendor.displayName),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
            Text(
                text = stringResource(guide.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            steps.forEachIndexed { index, step ->
                StepRow(index = index + 1, text = step)
                if (index != steps.lastIndex) Spacer(Modifier.height(MaterialTheme.spacing.small))
            }
            Spacer(Modifier.height(MaterialTheme.spacing.large))
            Button(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_reliability_action_app_info))
            }
            if (snapshot.vendor.needsAutostart) {
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                FilledTonalButton(
                    onClick = onOpenVendorManager,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_reliability_action_vendor_manager))
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            OutlinedButton(
                onClick = onConfirm,
                enabled = !confirmed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (confirmed) {
                            R.string.settings_reliability_action_marked_done
                        } else {
                            R.string.settings_reliability_action_mark_done
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ExplanationCard(modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.large)) {
            Text(
                text = stringResource(R.string.settings_reliability_explain_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
            Text(
                text = stringResource(R.string.settings_reliability_explain_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, body: String) {
    Column(modifier = Modifier.padding(MaterialTheme.spacing.large)) {
        Text(text = title, style = MaterialTheme.typography.titleMediumEmphasized)
        Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(
    title: String,
    body: String,
    status: String,
    tone: CheckTone,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(modifier = Modifier.padding(MaterialTheme.spacing.large)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.medium))
            StatusPill(text = status, tone = tone)
        }
        Spacer(Modifier.height(MaterialTheme.spacing.medium))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(MaterialTheme.spacing.xSmall))
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.size(MaterialTheme.spacing.medium))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusPill(text: String, tone: CheckTone) {
    val colors = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    when (tone) {
        CheckTone.Good -> {
            container = colors.primaryContainer
            content = colors.onPrimaryContainer
        }

        CheckTone.Info -> {
            container = colors.secondaryContainer
            content = colors.onSecondaryContainer
        }

        CheckTone.Warning -> {
            container = colors.errorContainer
            content = colors.onErrorContainer
        }
    }
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun PowerReliabilitySnapshot.isBackgroundRestricted(): Boolean =
    backgroundRestricted || standbyBucket == StandbyBucket.Restricted
