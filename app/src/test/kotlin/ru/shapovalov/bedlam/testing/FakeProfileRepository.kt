package ru.shapovalov.bedlam.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository

class FakeProfileRepository(
    initial: List<Profile> = emptyList(),
    activeId: String? = null,
) : ProfileRepository {

    val profiles = MutableStateFlow(initial)
    val active = MutableStateFlow(activeId)
    var observeAllGate: CompletableDeferred<Unit>? = null
    var upsertGate: CompletableDeferred<Unit>? = null
    var upsertFailure: Throwable? = null
    var deleteFailure: Throwable? = null
    var upsertCount = 0
        private set

    override fun observeAll(): Flow<List<Profile>> = flow {
        observeAllGate?.await()
        emitAll(profiles.map { list -> list.sortedByDescending { it.updatedAt } })
    }

    override fun observe(id: String): Flow<Profile?> =
        profiles.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun get(id: String): Profile? = profiles.value.firstOrNull { it.id == id }

    override suspend fun upsert(profile: Profile) {
        upsertCount++
        upsertGate?.await()
        upsertFailure?.let { throw it }
        profiles.update { list ->
            if (list.any { it.id == profile.id }) {
                list.map { if (it.id == profile.id) profile else it }
            } else {
                list + profile
            }
        }
    }

    override suspend fun delete(id: String) {
        deleteFailure?.let { throw it }
        profiles.update { list -> list.filterNot { it.id == id } }
    }

    override fun observeActiveId(): Flow<String?> = active

    override suspend fun getActiveId(): String? = active.value

    override suspend fun setActiveId(id: String?) {
        active.value = id
    }
}
