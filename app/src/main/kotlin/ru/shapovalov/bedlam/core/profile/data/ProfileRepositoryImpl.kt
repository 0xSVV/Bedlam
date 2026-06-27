package ru.shapovalov.bedlam.core.profile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import ru.shapovalov.bedlam.core.profile.data.local.AppSettingsDao
import ru.shapovalov.bedlam.core.profile.data.local.ProfileDao
import ru.shapovalov.bedlam.core.profile.data.mapper.toDomain
import ru.shapovalov.bedlam.core.profile.data.mapper.toEntity
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

@Inject
class ProfileRepositoryImpl(
    private val profileDao: ProfileDao,
    private val settingsDao: AppSettingsDao,
) : ProfileRepository {

    override fun observeAll(): Flow<List<Profile>> =
        profileDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observe(id: String): Flow<Profile?> =
        profileDao.observe(id).map { it?.toDomain() }

    override suspend fun get(id: String): Profile? = profileDao.get(id)?.toDomain()

    override suspend fun upsert(profile: Profile) {
        profileDao.upsert(profile.toEntity())
    }

    override suspend fun delete(id: String) {
        profileDao.delete(id)
    }

    override fun observeActiveId(): Flow<String?> = settingsDao.observeActiveProfileId()

    override suspend fun getActiveId(): String? = settingsDao.getActiveProfileId()

    override suspend fun setActiveId(id: String?) {
        settingsDao.setActiveProfileId(id)
    }
}
