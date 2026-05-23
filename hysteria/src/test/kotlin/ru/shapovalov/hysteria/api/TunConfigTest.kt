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
    fun `valid ipv4 cidr accepted`() {
        TunConfig(ipv4Prefix = "10.0.0.1/24")
    }

    @Test
    fun `valid ipv6 cidr accepted`() {
        TunConfig(ipv6Prefix = "fdfe::1/64")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "10.0.0.1",                // missing slash
            "10.0.0.1/",               // empty length
            "10.0.0.1/abc",            // non-numeric length
            "10.0.0.1/33",             // length > 32 for v4
            "256.0.0.0/24",            // octet out of range
            "10.0.0/24",               // wrong octet count
            "fdfe::1/24",              // ipv6 in ipv4 slot
            "10.0.0.1/16/32",          // multiple slashes
        ]
    )
    fun `bad ipv4 prefix rejected`(bad: String) {
        assertThrows(IllegalArgumentException::class.java) { TunConfig(ipv4Prefix = bad) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "fdfe::1",                 // missing slash
            "fdfe::1/",                // empty length
            "fdfe::1/129",             // length > 128
            "10.0.0.1/64",             // ipv4 in ipv6 slot
            "ggg::1/64",               // non-hex
            "1::2::3/64",              // two double-colons
            "1:2:3:4:5:6:7/64",        // too few groups without ::
            "1:2:3:4:5:6:7:8:9/64",    // too many groups
        ]
    )
    fun `bad ipv6 prefix rejected`(bad: String) {
        assertThrows(IllegalArgumentException::class.java) { TunConfig(ipv6Prefix = bad) }
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
