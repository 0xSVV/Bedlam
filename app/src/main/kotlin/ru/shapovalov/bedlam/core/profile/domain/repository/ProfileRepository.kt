package ru.shapovalov.bedlam.core.profile.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.shapovalov.bedlam.core.profile.domain.model.Profile

interface ProfileRepository {
    fun observeAll(): Flow<List<Profile>>
    fun observe(id: String): Flow<Profile?>
    suspend fun get(id: String): Profile?
    suspend fun upsert(profile: Profile)
    suspend fun delete(id: String)

    fun observeActiveId(): Flow<String?>
    suspend fun getActiveId(): String?
    suspend fun setActiveId(id: String?)
}
