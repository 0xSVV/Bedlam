package ru.shapovalov.bedlam.core.profile.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.profile.domain.model.DuplicateProfileException
import ru.shapovalov.bedlam.core.profile.domain.model.Profile
import ru.shapovalov.bedlam.core.profile.domain.model.ProfileImportFormat
import ru.shapovalov.bedlam.core.profile.domain.repository.ProfileRepository
import ru.shapovalov.hysteria.ConnectionState
import ru.shapovalov.hysteria.api.DiagnosticResult
import ru.shapovalov.hysteria.api.DisconnectReason
import ru.shapovalov.hysteria.api.HysteriaClient
import ru.shapovalov.hysteria.api.TunConfig
import ru.shapovalov.hysteria.config.HysteriaConfig

class ImportProfileUseCaseTest {

    private val link = "hysteria2://pw@example.com:443/?sni=example.com#Imported"

    // Mirrors the profile editor's Copy config output.
    private val clipboardJson = Json { prettyPrint = true; encodeDefaults = true }

    private class FakeRepo(initial: List<Profile> = emptyList()) : ProfileRepository {
        val profiles = initial.toMutableList()
        var activeId: String? = null

        override fun observeAll(): Flow<List<Profile>> = flowOf(profiles.toList())
        override fun observe(id: String): Flow<Profile?> =
            flowOf(profiles.firstOrNull { it.id == id })

        override suspend fun get(id: String): Profile? = profiles.firstOrNull { it.id == id }
        override suspend fun upsert(profile: Profile) {
            profiles.removeAll { it.id == profile.id }
            profiles += profile
        }

        override suspend fun delete(id: String) {
            profiles.removeAll { it.id == id }
        }

        override fun observeActiveId(): Flow<String?> = flowOf(activeId)
        override suspend fun getActiveId(): String? = activeId
        override suspend fun setActiveId(id: String?) {
            activeId = id
        }
    }

    private class FakeClient : HysteriaClient {
        override val state: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.Disconnected())

        override fun validateConfig(config: HysteriaConfig): Result<Unit> = Result.success(Unit)

        override suspend fun start(
            config: HysteriaConfig,
            tunConfig: TunConfig,
            protector: HysteriaClient.SocketProtector,
            tun: HysteriaClient.TunFactory,
        ) = Unit

        override suspend fun updateTun(
            tunConfig: TunConfig,
            tun: HysteriaClient.TunFactory,
        ) = Unit

        override suspend fun stop(reason: DisconnectReason) = Unit
        override fun shutdown(reason: DisconnectReason) = Unit
        override suspend fun closeSession() = Unit
        override suspend fun resetConnections() = Unit
        override suspend fun checkConnection() = Unit
        override fun stats(): HysteriaClient.TrafficStats? = null
        override fun logs(minLevel: HysteriaClient.LogLevel): Flow<HysteriaClient.LogEntry> =
            flowOf()

        override suspend fun testUdp(): DiagnosticResult = DiagnosticResult.Error("unused")
        override suspend fun testDnsOverTcp(): DiagnosticResult = DiagnosticResult.Error("unused")
    }

    private fun useCase(repo: FakeRepo) = ImportProfileUseCase(repo, FakeClient())

    @Test
    fun `imports a link into a new profile`() = runTest {
        val repo = FakeRepo()

        val result = useCase(repo)(link, ProfileImportFormat.Link)

        assertTrue(result.isSuccess)
        assertEquals(1, repo.profiles.size)
        assertEquals("Imported", repo.profiles.single().name)
    }

    @Test
    fun `rejects a link whose config is already saved`() = runTest {
        val repo = FakeRepo()
        useCase(repo)(link, ProfileImportFormat.Link).getOrThrow()

        val result = useCase(repo)(link, ProfileImportFormat.Link)

        val error = assertInstanceOf(
            DuplicateProfileException::class.java,
            result.exceptionOrNull(),
        )
        assertEquals("Imported", error.existingName)
        assertEquals(1, repo.profiles.size)
    }

    @Test
    fun `rejects a duplicate even when a different name is requested`() = runTest {
        val repo = FakeRepo()
        useCase(repo)(link, ProfileImportFormat.Link, name = "First").getOrThrow()

        val result = useCase(repo)(link, ProfileImportFormat.Link, name = "Second")

        val error = assertInstanceOf(
            DuplicateProfileException::class.java,
            result.exceptionOrNull(),
        )
        assertEquals("First", error.existingName)
        assertEquals(1, repo.profiles.size)
    }

    @Test
    fun `imports a link that differs from the saved one`() = runTest {
        val repo = FakeRepo()
        useCase(repo)(link, ProfileImportFormat.Link).getOrThrow()

        val result = useCase(repo)(
            "hysteria2://pw@other.example:443/?sni=other.example#Other",
            ProfileImportFormat.Link,
        )

        assertTrue(result.isSuccess)
        assertEquals(2, repo.profiles.size)
    }

    @Test
    fun `rejects a JSON copy of a link-imported profile`() = runTest {
        val repo = FakeRepo()
        val imported = useCase(repo)(link, ProfileImportFormat.Link).getOrThrow()
        val json = clipboardJson.encodeToString(HysteriaConfig.serializer(), imported.config)

        val result = useCase(repo)(json, ProfileImportFormat.Json)

        assertInstanceOf(DuplicateProfileException::class.java, result.exceptionOrNull())
        assertEquals(1, repo.profiles.size)
    }
}
