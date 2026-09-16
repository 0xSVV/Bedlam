package ru.shapovalov.bedlam.feature.settings.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket
import ru.shapovalov.bedlam.ui.theme.BedlamTheme

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class SettingsScreenPreviews

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 1800)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 360,
    heightDp = 1800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
private annotation class ReliabilityScreenPreviews

private const val PreviewFingerprint = "preview/fingerprint"

private val restrictedXiaomi = PowerReliabilitySnapshot(
    vendor = PowerVendor.Xiaomi,
    batteryUnrestricted = false,
    backgroundRestricted = false,
    standbyBucket = StandbyBucket.Rare,
    notificationsAllowed = false,
    alwaysOnVpnState = AlwaysOnVpnState.Disabled,
    riskLevel = PowerRiskLevel.High,
    buildFingerprint = PreviewFingerprint,
)

private val unrestrictedXiaomi = restrictedXiaomi.copy(
    batteryUnrestricted = true,
    standbyBucket = StandbyBucket.Active,
    notificationsAllowed = true,
    alwaysOnVpnState = AlwaysOnVpnState.Enabled,
)

private val healthyGeneric = PowerReliabilitySnapshot(
    vendor = PowerVendor.Generic,
    batteryUnrestricted = true,
    backgroundRestricted = false,
    standbyBucket = StandbyBucket.Exempted,
    notificationsAllowed = true,
    alwaysOnVpnState = AlwaysOnVpnState.EnabledWithLockdown,
    riskLevel = PowerRiskLevel.Low,
    buildFingerprint = PreviewFingerprint,
)

@Composable
private fun SettingsPreview(content: @Composable () -> Unit) {
    BedlamTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun SettingsRootPreview(
    quickSettingsTileAdded: Boolean,
    reliabilitySnapshot: PowerReliabilitySnapshot?,
) {
    SettingsPreview {
        SettingsRoot(
            onOpenAppSelection = {},
            onOpenRouting = {},
            onOpenBatteryReliability = {},
            quickSettingsTileAdded = quickSettingsTileAdded,
            onQuickSettingsTileAdded = {},
            reliabilitySnapshot = reliabilitySnapshot,
            confirmedReliabilityFingerprint = null,
        )
    }
}

@SettingsScreenPreviews
@Composable
private fun SettingsRootLoadingPreview() {
    SettingsRootPreview(quickSettingsTileAdded = true, reliabilitySnapshot = null)
}

@SettingsScreenPreviews
@Composable
private fun SettingsRootNeedsAttentionPreview() {
    SettingsRootPreview(quickSettingsTileAdded = false, reliabilitySnapshot = restrictedXiaomi)
}

@SettingsScreenPreviews
@Composable
private fun SettingsRootHealthyPreview() {
    SettingsRootPreview(quickSettingsTileAdded = true, reliabilitySnapshot = healthyGeneric)
}

@Composable
private fun ReliabilityPreview(
    snapshot: PowerReliabilitySnapshot?,
    confirmedFingerprint: String? = null,
) {
    SettingsPreview {
        BatteryReliabilityContent(
            snapshot = snapshot,
            confirmedFingerprint = confirmedFingerprint,
            onMarkConfirmed = {},
            onBack = {},
        )
    }
}

@ReliabilityScreenPreviews
@Composable
private fun BatteryReliabilityLoadingPreview() {
    ReliabilityPreview(snapshot = null)
}

@ReliabilityScreenPreviews
@Composable
private fun BatteryReliabilityNeedsAttentionPreview() {
    ReliabilityPreview(snapshot = restrictedXiaomi)
}

@ReliabilityScreenPreviews
@Composable
private fun BatteryReliabilityConfirmedPreview() {
    ReliabilityPreview(snapshot = unrestrictedXiaomi, confirmedFingerprint = PreviewFingerprint)
}

@ReliabilityScreenPreviews
@Composable
private fun BatteryReliabilityHealthyPreview() {
    ReliabilityPreview(snapshot = healthyGeneric)
}
