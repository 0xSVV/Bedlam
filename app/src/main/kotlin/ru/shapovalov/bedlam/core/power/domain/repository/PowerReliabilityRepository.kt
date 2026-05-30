package ru.shapovalov.bedlam.core.power.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot

interface PowerReliabilityRepository {
    val confirmedFingerprint: Flow<String?>

    fun snapshotNow(): PowerReliabilitySnapshot
    fun observeSnapshot(refreshIntervalMillis: Long): Flow<PowerReliabilitySnapshot>

    suspend fun markConfirmed(fingerprint: String)
    suspend fun writeAlwaysOnState(state: AlwaysOnVpnState)
}
