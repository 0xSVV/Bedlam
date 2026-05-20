package ru.shapovalov.bedlam.feature.session.domain.repository

import ru.shapovalov.bedlam.feature.session.domain.model.SessionInfo

interface SessionInfoRepository {
    suspend fun fetch(): Result<SessionInfo>
}
