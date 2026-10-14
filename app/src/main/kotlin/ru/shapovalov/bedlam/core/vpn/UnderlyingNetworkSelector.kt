package ru.shapovalov.bedlam.core.vpn

/** Stable selection policy for physical networks on Android 10 and 11. */
internal object UnderlyingNetworkSelector {
    enum class Transport(val preference: Int) { ETHERNET(0), WIFI(1), CELLULAR(2), OTHER(3) }

    data class Candidate(
        val id: String,
        val validated: Boolean,
        val transport: Transport,
    )

    fun select(
        candidates: Collection<Candidate>,
        activeId: String?,
        previousId: String?,
    ): String? {
        val byId = candidates.associateBy(Candidate::id)
        if (activeId != null && activeId in byId) return activeId
        if (previousId != null && previousId in byId) return previousId
        return candidates
            .sortedWith(
                compareByDescending<Candidate> { it.validated }
                    .thenBy { it.transport.preference }
                    .thenBy { it.id },
            )
            .firstOrNull()
            ?.id
    }
}
