package ru.shapovalov.bedlam.core.vpn

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.FakeHysteriaClient

class NetworkChangeResetTest {

    private val client = FakeHysteriaClient()

    @Test
    fun `a network change resets connections and keeps the DNS cache`() = runTest {
        client.resetAfterNetworkChange()

        assertEquals(listOf(FakeHysteriaClient.Reset.KEEPING_DNS_CACHE), client.resets)
    }
}
