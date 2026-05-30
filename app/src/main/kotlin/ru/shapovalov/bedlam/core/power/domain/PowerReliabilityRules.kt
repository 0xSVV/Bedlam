package ru.shapovalov.bedlam.core.power.domain

import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket

object PowerReliabilityRules {

    fun riskLevel(
        vendor: PowerVendor,
        batteryUnrestricted: Boolean,
        backgroundRestricted: Boolean,
        standbyBucket: StandbyBucket,
        notificationsAllowed: Boolean,
    ): PowerRiskLevel = when {
        backgroundRestricted || standbyBucket == StandbyBucket.Restricted -> PowerRiskLevel.High
        vendor.defaultRisk == PowerRiskLevel.High -> PowerRiskLevel.High
        vendor.defaultRisk == PowerRiskLevel.Medium ||
            !batteryUnrestricted ||
            !notificationsAllowed -> PowerRiskLevel.Medium
        else -> PowerRiskLevel.Low
    }

    fun needsAttention(
        snapshot: PowerReliabilitySnapshot,
        confirmedFingerprint: String?,
    ): Boolean {
        val vendorNeedsReview = snapshot.vendor.needsManualBackgroundAccess &&
            confirmedFingerprint != snapshot.buildFingerprint
        return vendorNeedsReview ||
            !snapshot.batteryUnrestricted ||
            snapshot.backgroundRestricted ||
            snapshot.standbyBucket == StandbyBucket.Restricted ||
            !snapshot.notificationsAllowed
    }

    fun effectiveAlwaysOnState(
        secureState: AlwaysOnVpnState?,
        recentObservedState: AlwaysOnVpnState?,
    ): AlwaysOnVpnState = when {
        recentObservedState == AlwaysOnVpnState.Enabled ||
            recentObservedState == AlwaysOnVpnState.EnabledWithLockdown -> recentObservedState
        secureState != null -> secureState
        recentObservedState != null -> recentObservedState
        else -> AlwaysOnVpnState.Unknown
    }
}
