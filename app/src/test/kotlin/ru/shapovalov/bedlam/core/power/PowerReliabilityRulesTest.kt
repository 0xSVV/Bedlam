package ru.shapovalov.bedlam.core.power.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.core.power.domain.model.PowerVendor
import ru.shapovalov.bedlam.core.power.domain.model.StandbyBucket

class PowerReliabilityRulesTest {

    @Nested
    inner class VendorDetection {

        @ParameterizedTest
        @CsvSource(
            "OnePlus CPH2581, OnePlus",
            "OPPO CPH2609, Oppo",
            "realme RMX3700, Realme",
            "Xiaomi POCO F6, Xiaomi",
            "Redmi 2312DRA50G, Xiaomi",
            "Honor Magic6, Honor",
            "Huawei LYA-L29, Huawei",
            "vivo iQOO 12, Vivo",
            "TECNO CK8n, Transsion",
            "Infinix X6871, Transsion",
            "asus ASUS_AI2401, Asus",
            "Meizu 21, Meizu",
            "Lenovo TB-X606F, Lenovo",
            "Samsung SM-S921B, Samsung",
            "Google Pixel 9, Generic",
        )
        fun `detects known vendors`(maker: String, expectedName: String) {
            assertEquals(expectedName, PowerVendor.from(maker).name)
        }
    }

    @Nested
    inner class RiskLevel {

        @Test
        fun `restricted Android state is high risk even on normal vendor`() {
            val risk = PowerReliabilityRules.riskLevel(
                vendor = PowerVendor.Generic,
                batteryUnrestricted = true,
                backgroundRestricted = false,
                standbyBucket = StandbyBucket.Restricted,
                notificationsAllowed = true,
            )

            assertEquals(PowerRiskLevel.High, risk)
        }

        @Test
        fun `aggressive vendor is high risk even when Android switches are allowed`() {
            val risk = PowerReliabilityRules.riskLevel(
                vendor = PowerVendor.OnePlus,
                batteryUnrestricted = true,
                backgroundRestricted = false,
                standbyBucket = StandbyBucket.Active,
                notificationsAllowed = true,
            )

            assertEquals(PowerRiskLevel.High, risk)
        }

        @Test
        fun `blocked notifications raise otherwise normal Android to medium risk`() {
            val risk = PowerReliabilityRules.riskLevel(
                vendor = PowerVendor.Generic,
                batteryUnrestricted = true,
                backgroundRestricted = false,
                standbyBucket = StandbyBucket.Active,
                notificationsAllowed = false,
            )

            assertEquals(PowerRiskLevel.Medium, risk)
        }
    }

    @Nested
    inner class AttentionState {

        @Test
        fun `risky vendor needs attention until current build fingerprint is confirmed`() {
            val snapshot = snapshot(vendor = PowerVendor.Xiaomi, buildFingerprint = "build-a")

            assertTrue(PowerReliabilityRules.needsAttention(snapshot, confirmedFingerprint = null))
            assertTrue(PowerReliabilityRules.needsAttention(snapshot, confirmedFingerprint = "old"))
            assertFalse(
                PowerReliabilityRules.needsAttention(
                    snapshot,
                    confirmedFingerprint = "build-a",
                )
            )
        }

        @Test
        fun `confirmed vendor still needs attention when Android battery switch is restricted`() {
            val snapshot = snapshot(
                vendor = PowerVendor.Xiaomi,
                batteryUnrestricted = false,
                buildFingerprint = "build-a",
            )

            assertTrue(
                PowerReliabilityRules.needsAttention(
                    snapshot,
                    confirmedFingerprint = "build-a",
                )
            )
        }

        @Test
        fun `normal Android without restrictions does not need attention`() {
            val snapshot = snapshot(vendor = PowerVendor.Samsung)

            assertFalse(PowerReliabilityRules.needsAttention(snapshot, confirmedFingerprint = null))
        }
    }

    @Nested
    inner class AlwaysOnState {

        @Test
        fun `recent service observation wins when secure settings look stale`() {
            val state = PowerReliabilityRules.effectiveAlwaysOnState(
                secureState = AlwaysOnVpnState.Disabled,
                recentObservedState = AlwaysOnVpnState.Enabled,
            )

            assertEquals(AlwaysOnVpnState.Enabled, state)
        }

        @Test
        fun `secure settings win over non-enabled service observation`() {
            val state = PowerReliabilityRules.effectiveAlwaysOnState(
                secureState = AlwaysOnVpnState.EnabledWithLockdown,
                recentObservedState = AlwaysOnVpnState.Disabled,
            )

            assertEquals(AlwaysOnVpnState.EnabledWithLockdown, state)
        }

        @Test
        fun `unknown is used when no source is available`() {
            val state = PowerReliabilityRules.effectiveAlwaysOnState(
                secureState = null,
                recentObservedState = null,
            )

            assertEquals(AlwaysOnVpnState.Unknown, state)
        }
    }

    private fun snapshot(
        vendor: PowerVendor,
        batteryUnrestricted: Boolean = true,
        backgroundRestricted: Boolean = false,
        standbyBucket: StandbyBucket = StandbyBucket.Active,
        notificationsAllowed: Boolean = true,
        buildFingerprint: String = "fingerprint",
    ): PowerReliabilitySnapshot =
        PowerReliabilitySnapshot(
            vendor = vendor,
            batteryUnrestricted = batteryUnrestricted,
            backgroundRestricted = backgroundRestricted,
            standbyBucket = standbyBucket,
            notificationsAllowed = notificationsAllowed,
            alwaysOnVpnState = AlwaysOnVpnState.Disabled,
            riskLevel = PowerReliabilityRules.riskLevel(
                vendor = vendor,
                batteryUnrestricted = batteryUnrestricted,
                backgroundRestricted = backgroundRestricted,
                standbyBucket = standbyBucket,
                notificationsAllowed = notificationsAllowed,
            ),
            buildFingerprint = buildFingerprint,
        )
}
