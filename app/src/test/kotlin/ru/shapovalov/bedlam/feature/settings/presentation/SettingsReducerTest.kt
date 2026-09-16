package ru.shapovalov.bedlam.feature.settings.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.power.domain.model.PowerRiskLevel
import ru.shapovalov.bedlam.testing.powerSnapshot
import ru.shapovalov.bedlam.testing.reduceAll

class SettingsReducerTest {

    private val initial = SettingsStore.State(reliabilitySnapshot = powerSnapshot())

    @Test
    fun `each message updates only its field`() {
        assertEquals(
            initial.copy(quickSettingsTileAdded = true),
            SettingsReducer.reduceAll(initial, Msg.QuickSettingsTileAddedChanged(true)),
        )

        val risky = powerSnapshot(risk = PowerRiskLevel.High, fingerprint = "fp2")
        assertEquals(
            initial.copy(reliabilitySnapshot = risky),
            SettingsReducer.reduceAll(initial, Msg.ReliabilitySnapshotChanged(risky)),
        )

        assertEquals(
            initial.copy(confirmedReliabilityFingerprint = "fp"),
            SettingsReducer.reduceAll(initial, Msg.ConfirmedReliabilityFingerprintChanged("fp")),
        )
    }

    @Test
    fun `a cleared confirmation forgets the fingerprint`() {
        val confirmed = initial.copy(confirmedReliabilityFingerprint = "fp")

        assertEquals(
            initial,
            SettingsReducer.reduceAll(confirmed, Msg.ConfirmedReliabilityFingerprintChanged(null)),
        )
    }
}
