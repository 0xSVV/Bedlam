package ru.shapovalov.bedlam.feature.settings.ui

import ru.shapovalov.bedlam.R
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket

internal data class VendorGuide(
    val titleRes: Int,
    val bodyRes: Int,
    val stepsArrayRes: Int,
)

internal enum class CheckTone { Good, Info, Warning }

internal fun PowerRiskLevel.labelRes(): Int = when (this) {
    PowerRiskLevel.Low -> R.string.settings_reliability_risk_low
    PowerRiskLevel.Medium -> R.string.settings_reliability_risk_medium
    PowerRiskLevel.High -> R.string.settings_reliability_risk_high
}

internal fun AlwaysOnVpnState.labelRes(): Int = when (this) {
    AlwaysOnVpnState.Enabled -> R.string.settings_reliability_status_enabled
    AlwaysOnVpnState.EnabledWithLockdown -> R.string.settings_reliability_status_lockdown
    AlwaysOnVpnState.Disabled -> R.string.settings_reliability_status_off
    AlwaysOnVpnState.OtherVpn -> R.string.settings_reliability_status_other_vpn
    AlwaysOnVpnState.Unknown -> R.string.settings_reliability_status_unknown
}

internal fun StandbyBucket.labelRes(): Int = when (this) {
    StandbyBucket.Active -> R.string.settings_reliability_bucket_active
    StandbyBucket.WorkingSet -> R.string.settings_reliability_bucket_working_set
    StandbyBucket.Frequent -> R.string.settings_reliability_bucket_frequent
    StandbyBucket.Rare -> R.string.settings_reliability_bucket_rare
    StandbyBucket.Restricted -> R.string.settings_reliability_bucket_restricted
    StandbyBucket.Exempted -> R.string.settings_reliability_bucket_exempted
    StandbyBucket.Unknown -> R.string.settings_reliability_bucket_unknown
}

internal fun PowerVendor.guide(): VendorGuide = when (this) {
    PowerVendor.OnePlus -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_oneplus,
        stepsArrayRes = R.array.settings_reliability_steps_oneplus,
    )
    PowerVendor.Oppo,
    PowerVendor.Realme -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_coloros,
        stepsArrayRes = R.array.settings_reliability_steps_coloros,
    )
    PowerVendor.Xiaomi -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_xiaomi,
        stepsArrayRes = R.array.settings_reliability_steps_xiaomi,
    )
    PowerVendor.Huawei,
    PowerVendor.Honor -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_huawei,
        stepsArrayRes = R.array.settings_reliability_steps_huawei,
    )
    PowerVendor.Vivo -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_vivo,
        stepsArrayRes = R.array.settings_reliability_steps_vivo,
    )
    PowerVendor.Transsion,
    PowerVendor.Asus,
    PowerVendor.Meizu,
    PowerVendor.Lenovo -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_generic_aggressive,
        stepsArrayRes = R.array.settings_reliability_steps_generic_aggressive,
    )
    PowerVendor.Samsung -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_samsung,
        stepsArrayRes = R.array.settings_reliability_steps_samsung,
    )
    PowerVendor.Generic -> VendorGuide(
        titleRes = R.string.settings_reliability_vendor_title,
        bodyRes = R.string.settings_reliability_vendor_body_normal,
        stepsArrayRes = R.array.settings_reliability_steps_normal,
    )
}
