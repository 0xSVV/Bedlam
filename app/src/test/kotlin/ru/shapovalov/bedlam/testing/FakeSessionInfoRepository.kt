package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.CompletableDeferred
import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo
import ru.shapovalov.bedlam.feature.session.domain.repository.SessionInfoRepository

class FakeSessionInfoRepository(
    var result: Result<SessionInfo> = Result.success(sessionInfo()),
) : SessionInfoRepository {

    var gate: CompletableDeferred<Unit>? = null
    var fetchCount = 0
        private set

    override suspend fun fetch(): Result<SessionInfo> {
        fetchCount++
        gate?.await()
        return result
    }
}
