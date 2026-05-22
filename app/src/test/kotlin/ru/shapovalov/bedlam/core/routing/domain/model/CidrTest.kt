package ru.shapovalov.bedlam.core.routing.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CidrTest {

    @Test
    fun `parses a basic v4 cidr`() {
        val c = Cidr.parse("192.168.1.0/24") as Cidr.V4
        assertEquals(24, c.prefixLength)
        assertEquals("192.168.1.0/24", c.asString())
    }

    @Test
    fun `parses a basic v6 cidr`() {
        val c = Cidr.parse("2001:db8::/32") as Cidr.V6
        assertEquals(32, c.prefixLength)
        assertTrue(c.asString().startsWith("2001:db8"))
    }

    @ParameterizedTest
    @CsvSource(
        "192.168.1.5/24,   192.168.1.0/24",   // host bits truncated
        "10.123.45.67/8,   10.0.0.0/8",
        "1.1.1.1/32,       1.1.1.1/32",
        "0.0.0.0/0,        0.0.0.0/0",
    )
    fun `host bits are normalized for v4`(input: String, expected: String) {
        assertEquals(expected, Cidr.parse(input).asString())
    }

    @ParameterizedTest
    @CsvSource(
        "2001:db8:1234::1/32, 2001:db8::/32",
        "fe80::1/10,          fe80::/10",
        "::1/128,             ::1/128",
        "fc00::/7,            fc00::/7",
    )
    fun `host bits are normalized for v6`(input: String, expected: String) {
        assertEquals(expected, Cidr.parse(input).asString())
    }

    @Test
    fun `parse rejects garbage`() {
        assertThrows(IllegalArgumentException::class.java) { Cidr.parse("not-a-cidr") }
        assertThrows(IllegalArgumentException::class.java) { Cidr.parse("192.168.1.0") }
        assertThrows(IllegalArgumentException::class.java) { Cidr.parse("192.168.1.0/33") }
        assertThrows(IllegalArgumentException::class.java) { Cidr.parse("999.999.999.999/8") }
    }

    @Test
    fun `parseOrNull swallows errors`() {
        assertNull(Cidr.parseOrNull("nope"))
        assertNull(Cidr.parseOrNull("10.0.0.0/abc"))
    }

    @Test
    fun `equal cidrs hash-collide`() {
        val a = Cidr.parse("10.0.0.0/8")
        val b = Cidr.parse("10.99.99.99/8")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
