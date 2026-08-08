package ru.shapovalov.bedlam.core.routing.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver
import ru.shapovalov.bedlam.core.routing.domain.repository.RoutingRepository

class RefreshRouteSourcesUseCaseTest {

    private val asn = DirectRouteSource.Asn("s1", 13335, "", enabled = true, orderIndex = 0)
    private val previous = listOf(Cidr.parse("1.1.1.0/24"), Cidr.parse("1.0.0.0/24"))

    private class FakeRepo(config: RoutingConfig) : RoutingRepository {
        var current = config
        val resolutions = mutableListOf<Pair<String, List<Cidr>>>()
        val errors = mutableListOf<Pair<String, String?>>()

        override fun observe(): Flow<RoutingConfig> = flowOf(current)
        override suspend fun get(): RoutingConfig = current
        override suspend fun setBypassLan(enabled: Boolean) = Unit
        override suspend fun setIpv6Mode(mode: Ipv6Mode) = Unit
        override suspend fun setDnsMode(mode: DnsMode) = Unit
        override suspend fun setCustomDns(servers: List<String>) = Unit
        override suspend fun upsertSource(source: DirectRouteSource) = Unit
        override suspend fun removeSource(id: String) = Unit
        override suspend fun setSourceEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun hasEquivalent(source: DirectRouteSource): Boolean = false

        override suspend fun recordResolution(
            sourceId: String,
            cidrs: List<Cidr>,
            error: String?,
        ) {
            resolutions += sourceId to cidrs
        }

        override suspend fun recordResolutionError(sourceId: String, error: String?) {
            errors += sourceId to error
        }
    }

    private fun repoWithResolvedSource() = FakeRepo(
        RoutingConfig(
            sources = listOf(
                ResolvedSource(
                    source = asn,
                    cidrs = previous,
                    lastResolvedMillis = 1_000L,
                    lastError = null,
                )
            )
        )
    )

    @Test
    fun `a failed refresh records the error without touching resolved routes`() = runTest {
        val repo = repoWithResolvedSource()
        val resolver = DirectRouteResolver { Result.failure(IllegalStateException("HTTP 429")) }

        RefreshRouteSourcesUseCase(repo, resolver)()

        assertTrue(repo.resolutions.isEmpty())
        assertEquals(listOf("s1" to "HTTP 429"), repo.errors)
    }

    @Test
    fun `a successful refresh replaces the resolved routes and clears the error`() = runTest {
        val repo = repoWithResolvedSource()
        val fresh = listOf(Cidr.parse("104.16.0.0/13"))
        val resolver = DirectRouteResolver { Result.success(fresh) }

        RefreshRouteSourcesUseCase(repo, resolver)()

        assertEquals(listOf("s1" to fresh), repo.resolutions)
        assertTrue(repo.errors.isEmpty())
    }

    @Test
    fun `a failed add records the error so the source stays stale`() = runTest {
        val repo = FakeRepo(RoutingConfig())
        val resolver = DirectRouteResolver { Result.failure(IllegalStateException("offline")) }

        val added = AddRouteSourceUseCase(repo, resolver)(asn)

        assertTrue(added)
        assertTrue(repo.resolutions.isEmpty())
        assertEquals(listOf("s1" to "offline"), repo.errors)
    }

    @Test
    fun `cidr sources are never re-resolved`() = runTest {
        val cidr = Cidr.parse("10.0.0.0/8")
        val repo = FakeRepo(
            RoutingConfig(
                sources = listOf(
                    ResolvedSource(
                        source = DirectRouteSource.Cidr("s2", cidr, "", true, 0),
                        cidrs = listOf(cidr),
                        lastResolvedMillis = null,
                        lastError = null,
                    )
                )
            )
        )
        var called = false
        val resolver = DirectRouteResolver {
            called = true
            Result.success(emptyList())
        }

        RefreshRouteSourcesUseCase(repo, resolver)()

        assertNull(called.takeIf { it })
        assertTrue(repo.resolutions.isEmpty())
        assertTrue(repo.errors.isEmpty())
    }
}

private fun DirectRouteResolver(
    block: suspend (DirectRouteSource) -> Result<List<Cidr>>,
): DirectRouteResolver = object : DirectRouteResolver {
    override suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>> = block(source)
}
