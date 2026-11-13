package ru.shapovalov.bedlam.feature.routing.presentation

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.core.routing.domain.repository.DirectRouteResolver
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddPresetUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.AddRouteSourceUseCase
import ru.shapovalov.bedlam.core.routing.domain.usecase.RefreshRouteSourcesUseCase
import ru.shapovalov.bedlam.testing.FakeRoutingRepository
import ru.shapovalov.bedlam.testing.MainDispatcherExtension
import ru.shapovalov.bedlam.testing.disposeAfter

@ExtendWith(MainDispatcherExtension::class)
class RoutingExecutorTest {
    private val source = ResolvedSource(
        DirectRouteSource.Asn("cloud", 13335, "Cloud network", enabled = false, orderIndex = 4),
        listOf(Cidr.parse("192.0.2.0/24")),
        1234L,
        "Previous refresh failed",
    )
    private val resolver = object : DirectRouteResolver {
        override suspend fun resolve(source: DirectRouteSource): Result<List<Cidr>> =
            error("Undo must restore cached routes without a network refresh")
    }

    private fun store(repository: FakeRoutingRepository): RoutingStore {
        val add = AddRouteSourceUseCase(repository, resolver)
        return RoutingStoreFactory(
            DefaultStoreFactory(),
            repository,
            add,
            AddPresetUseCase(add),
            RefreshRouteSourcesUseCase(repository, resolver),
        ).create()
    }

    @Test
    fun `direct DNS saves provider transport endpoints and bootstrap together`() = runTest {
        val repository = FakeRoutingRepository()
        store(repository).disposeAfter { store ->
            val bootstrap = mapOf("dns.example" to listOf("192.0.2.1"))
            store.accept(
                RoutingStore.Intent.SetDirectDns(
                    ru.shapovalov.bedlam.core.routing.domain.model.DnsMode.Custom,
                    ru.shapovalov.hysteria.api.DnsTransport.Https,
                    listOf("https://dns.example/dns-query"), bootstrap,
                ),
            )
            assertEquals(bootstrap, repository.config.value.directDnsBootstrap)
            assertEquals(ru.shapovalov.hysteria.api.DnsTransport.Https, repository.config.value.directDnsTransport)
            assertEquals(listOf("https://dns.example/dns-query"), repository.config.value.directCustomDns)
        }
    }

    @Test
    fun `undo restores the full source including cached networks and resolution metadata`() = runTest {
        val repository = FakeRoutingRepository(RoutingConfig(sources = listOf(source)))
        store(repository).disposeAfter { store ->
            store.accept(RoutingStore.Intent.RemoveSource(source.source.id))
            assertTrue(repository.config.value.sources.isEmpty())
            assertEquals(source, store.state.removedSource)
            store.accept(RoutingStore.Intent.UndoRemoveSource(source.source.id))
            assertEquals(listOf(source), repository.config.value.sources)
            assertNull(store.state.removedSource)
        }
    }

    @Test
    fun `stale snackbar dismissal cannot clear a newer deletion`() = runTest {
        val second = source.copy(source = DirectRouteSource.Domain("second", "example.org", "", true, 5))
        val repository = FakeRoutingRepository(RoutingConfig(sources = listOf(source, second)))
        store(repository).disposeAfter { store ->
            store.accept(RoutingStore.Intent.RemoveSource(source.source.id))
            store.accept(RoutingStore.Intent.RemoveSource(second.source.id))
            store.accept(RoutingStore.Intent.DismissRemoval(source.source.id))
            assertEquals(second, store.state.removedSource)
            store.accept(RoutingStore.Intent.UndoRemoveSource(second.source.id))
            assertEquals(listOf(second), repository.config.value.sources)
        }
    }
}
