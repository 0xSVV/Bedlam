package ru.shapovalov.bedlam.feature.routing.presentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.bedlam.testing.reduceAll

class RoutingReducerTest {

    @Test
    fun `config and refreshing messages update their fields`() {
        val config = RoutingConfig(
            bypassLan = false,
            ipv6Mode = Ipv6Mode.Disabled,
            dnsMode = DnsMode.Custom,
        )

        val changed = RoutingReducer.reduceAll(RoutingStore.State(), Msg.ConfigChanged(config))
        assertEquals(RoutingStore.State(config = config), changed)

        val refreshing = RoutingReducer.reduceAll(changed, Msg.RefreshingChanged(true))
        assertEquals(RoutingStore.State(config = config, isRefreshing = true), refreshing)

        val done = RoutingReducer.reduceAll(refreshing, Msg.RefreshingChanged(false))
        assertEquals(changed, done)
    }
}
