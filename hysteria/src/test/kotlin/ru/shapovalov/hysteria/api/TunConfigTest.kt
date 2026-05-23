package ru.shapovalov.hysteria.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TunConfigTest {

    @Test
    fun `defaults construct without error`() {
        val c = TunConfig.Default
        assertEquals(1280, c.mtu)
    }

    @Test
    fun `internal CIDR constants are well-formed`() {
        requireCidr(TunConfig.IPV4_CIDR, "IPV4_CIDR", IpFamily.V4)
        requireCidr(TunConfig.IPV6_CIDR, "IPV6_CIDR", IpFamily.V6)
        assertEquals("${TunConfig.IPV4_ADDRESS}/${TunConfig.IPV4_PREFIX_LENGTH}", TunConfig.IPV4_CIDR)
        assertEquals("${TunConfig.IPV6_ADDRESS}/${TunConfig.IPV6_PREFIX_LENGTH}", TunConfig.IPV6_CIDR)
    }

    @ParameterizedTest
    @ValueSource(ints = [TunConfig.MIN_MTU, 1280, 1500, TunConfig.MAX_MTU])
    fun `mtu in range accepted`(mtu: Int) {
        TunConfig(mtu = mtu)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, TunConfig.MIN_MTU - 1, TunConfig.MAX_MTU + 1, Int.MAX_VALUE])
    fun `mtu out of range rejected`(mtu: Int) {
        assertThrows(IllegalArgumentException::class.java) { TunConfig(mtu = mtu) }
    }

    @Test
    fun `ipv4 family detection`() {
        assertEquals(IpFamily.V4, numericIpFamily("0.0.0.0"))
        assertEquals(IpFamily.V4, numericIpFamily("255.255.255.255"))
        assertEquals(IpFamily.V4, numericIpFamily("192.168.0.1"))
        assertNull(numericIpFamily("256.0.0.0"))
        assertNull(numericIpFamily("1.2.3"))
    }

    @Test
    fun `ipv6 family detection`() {
        assertEquals(IpFamily.V6, numericIpFamily("::"))
        assertEquals(IpFamily.V6, numericIpFamily("::1"))
        assertEquals(IpFamily.V6, numericIpFamily("fe80::1"))
        assertEquals(IpFamily.V6, numericIpFamily("2001:db8::"))
        assertEquals(IpFamily.V6, numericIpFamily("1:2:3:4:5:6:7:8"))
        assertEquals(IpFamily.V6, numericIpFamily("ABCD:ef::1"))
    }

    @Test
    fun `non-numeric strings rejected`() {
        assertNull(numericIpFamily("host.example"))
        assertNull(numericIpFamily(""))
        assertNull(numericIpFamily("not an ip"))
    }

    @Test
    fun `ipv6 helper edge cases`() {
        assertTrue(isNumericIpv6("::"))
        assertTrue(isNumericIpv6("::1"))
        assertTrue(isNumericIpv6("1::"))
        assertFalse(isNumericIpv6(":1"))
        assertFalse(isNumericIpv6("1:"))
        assertFalse(isNumericIpv6("12345::1")) // group > 4 chars
    }
}
