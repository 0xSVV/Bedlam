package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import ru.shapovalov.bedlam.core.power.domain.model.AlwaysOnVpnState
import ru.shapovalov.bedlam.core.power.domain.model.PowerReliabilitySnapshot
import ru.shapovalov.bedlam.core.power.domain.repository.PowerReliabilityRepository

class FakePowerReliabilityRepository(
    initial: PowerReliabilitySnapshot = powerSnapshot(),
) : PowerReliabilityRepository {

    val snapshots = MutableStateFlow(initial)
    val confirmed = MutableStateFlow<String?>(null)
    val pollIntervals = mutableListOf<Long>()
    val confirmedFingerprints = mutableListOf<String>()
    var activePolls = 0
        private set

    override val confirmedFingerprint: Flow<String?> = confirmed

    override fun observeSnapshot(refreshIntervalMillis: Long): Flow<PowerReliabilitySnapshot> =
        snapshots
            .onStart {
                activePolls++
                pollIntervals += refreshIntervalMillis
            }
            .onCompletion { activePolls-- }

    override suspend fun markConfirmed(fingerprint: String) {
        confirmedFingerprints += fingerprint
        confirmed.value = fingerprint
    }

    override suspend fun writeAlwaysOnState(state: AlwaysOnVpnState) = Unit
}
