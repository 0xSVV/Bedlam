package ru.shapovalov.bedlam.core.latency

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import java.net.InetAddress

@Inject
class PingProfileUseCase {
    suspend operator fun invoke(profile: Profile): LatencyResult = withContext(Dispatchers.IO) {
        val host = parseHost(profile.config.server.address)
        try {
            val start = System.currentTimeMillis()
            val reached = InetAddress.getByName(host).isReachable(TIMEOUT_MS)
            val ms = System.currentTimeMillis() - start
            if (reached) LatencyResult.Success(ms) else LatencyResult.Unreachable
        } catch (_: Exception) {
            LatencyResult.Unreachable
        }
    }

    private fun parseHost(address: String): String =
        if (address.startsWith("[")) address.removePrefix("[").substringBefore("]")
        else address.substringBeforeLast(":")

    private companion object {
        const val TIMEOUT_MS = 3000
    }
}
