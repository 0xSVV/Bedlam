package ru.shapovalov.bedlam.core.vpn

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnderlyingNetworkSelectorTest {
    private val wifi = UnderlyingNetworkSelector.Candidate(
        "wifi", validated = true, UnderlyingNetworkSelector.Transport.WIFI,
    )
    private val mobile = UnderlyingNetworkSelector.Candidate(
        "mobile", validated = true, UnderlyingNetworkSelector.Transport.CELLULAR,
    )

    @Test
    fun `selection does not depend on callback arrival order`() {
        val first = UnderlyingNetworkSelector.select(listOf(wifi, mobile), null, null)
        val reversed = UnderlyingNetworkSelector.select(listOf(mobile, wifi), null, null)
        assertEquals("wifi", first)
        assertEquals(first, reversed)
    }

    @Test
    fun `identifiable active network wins over callback ordering`() {
        assertEquals("mobile", UnderlyingNetworkSelector.select(listOf(wifi, mobile), "mobile", null))
    }

    @Test
    fun `eligible prior network is retained until it is lost`() {
        assertEquals("mobile", UnderlyingNetworkSelector.select(listOf(wifi, mobile), null, "mobile"))
        assertEquals("wifi", UnderlyingNetworkSelector.select(listOf(wifi), null, "mobile"))
    }

    @Test
    fun `validated candidate wins before transport preference`() {
        val unvalidatedEthernet = UnderlyingNetworkSelector.Candidate(
            "ethernet", validated = false, UnderlyingNetworkSelector.Transport.ETHERNET,
        )
        assertEquals("wifi", UnderlyingNetworkSelector.select(listOf(unvalidatedEthernet, wifi), null, null))
    }
}
