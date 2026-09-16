package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.CompletableDeferred
import ru.shapovalov.bedlam.core.latency.LatencyResult
import ru.shapovalov.bedlam.core.profile.domain.model.Profile

class FakeProfilePinger {

    val calls = mutableListOf<Pair<String, CompletableDeferred<LatencyResult>>>()
    var active = 0
        private set

    suspend fun ping(profile: Profile): LatencyResult {
        val result = CompletableDeferred<LatencyResult>()
        calls += profile.id to result
        active++
        try {
            return result.await()
        } finally {
            active--
        }
    }
}
