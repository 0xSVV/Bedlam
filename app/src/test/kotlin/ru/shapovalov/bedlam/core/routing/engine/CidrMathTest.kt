package ru.shapovalov.bedlam.core.routing.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr

class CidrMathTest {

    @Nested
    inner class Contains {

        @ParameterizedTest
        @CsvSource(
            "0.0.0.0/0,      192.168.1.0/24, true",
            "192.168.0.0/16, 192.168.1.0/24, true",
            "192.168.0.0/16, 192.168.0.0/16, true",
            "192.168.1.0/24, 192.168.0.0/16, false",
            "10.0.0.0/8,     192.168.0.0/16, false",
            "::/0,           2001:db8::/32,  true",
            "fc00::/7,       fd12:3456::/32, true",
            "fc00::/7,       fe80::/10,      false",
        )
        fun `contains works for both families`(outer: String, inner: String, expected: Boolean) {
            assertEquals(expected, CidrMath.contains(Cidr.parse(outer), Cidr.parse(inner)))
        }

        @Test
        fun `contains is false across families`() {
            assertFalse(CidrMath.contains(Cidr.parse("0.0.0.0/0"), Cidr.parse("::/0")))
        }
    }

    @Nested
    inner class Overlaps {

        @ParameterizedTest
        @CsvSource(
            "192.168.0.0/16, 192.168.1.0/24, true",
            "192.168.1.0/24, 192.168.0.0/16, true",
            "10.0.0.0/8,     192.168.0.0/16, false",
            "10.0.0.0/8,     10.5.0.0/16,    true",
            "10.0.0.0/9,     10.128.0.0/9,   false",
        )
        fun `overlaps for v4`(a: String, b: String, expected: Boolean) {
            assertEquals(expected, CidrMath.overlaps(Cidr.parse(a), Cidr.parse(b)))
        }
    }

    @Nested
    inner class Coalesce {

        @Test
        fun `two buddy cidrs merge into parent`() {
            val merged = CidrMath.coalesce(
                listOf(Cidr.parse("10.0.0.0/9"), Cidr.parse("10.128.0.0/9"))
            )
            assertEquals(listOf(Cidr.parse("10.0.0.0/8")), merged)
        }

        @Test
        fun `four blocks collapse into one slash-22 parent`() {
            val merged = CidrMath.coalesce(
                listOf(
                    Cidr.parse("192.168.0.0/24"),
                    Cidr.parse("192.168.1.0/24"),
                    Cidr.parse("192.168.2.0/24"),
                    Cidr.parse("192.168.3.0/24"),
                )
            )
            assertEquals(listOf(Cidr.parse("192.168.0.0/22")), merged)
        }

        @Test
        fun `non-adjacent blocks stay split`() {
            val merged = CidrMath.coalesce(
                listOf(Cidr.parse("10.0.0.0/8"), Cidr.parse("192.168.0.0/16"))
            )
            assertEquals(
                setOf(Cidr.parse("10.0.0.0/8"), Cidr.parse("192.168.0.0/16")),
                merged.toSet(),
            )
        }

        @Test
        fun `redundant entries are dropped`() {
            val merged = CidrMath.coalesce(
                listOf(
                    Cidr.parse("10.0.0.0/8"),
                    Cidr.parse("10.5.0.0/16"),
                    Cidr.parse("10.5.6.0/24"),
                )
            )
            assertEquals(listOf(Cidr.parse("10.0.0.0/8")), merged)
        }

        @Test
        fun `mixed families are partitioned`() {
            val merged = CidrMath.coalesce(
                listOf(Cidr.parse("10.0.0.0/8"), Cidr.parse("fc00::/7"))
            )
            assertEquals(
                setOf(Cidr.parse("10.0.0.0/8"), Cidr.parse("fc00::/7")),
                merged.toSet(),
            )
        }

        @Test
        fun `empty input returns empty`() {
            assertTrue(CidrMath.coalesce(emptyList()).isEmpty())
        }

        @Test
        fun `single full v4 sweep merges to slash-0`() {
            val merged = CidrMath.coalesce(
                listOf(Cidr.parse("0.0.0.0/1"), Cidr.parse("128.0.0.0/1"))
            )
            assertEquals(listOf(Cidr.parse("0.0.0.0/0")), merged)
        }

        @Test
        fun `slash-32 host buddies merge`() {
            val merged = CidrMath.coalesce(
                listOf(Cidr.parse("10.0.0.0/32"), Cidr.parse("10.0.0.1/32"))
            )
            assertEquals(listOf(Cidr.parse("10.0.0.0/31")), merged)
        }
    }

    @Nested
    inner class Subtract {

        @Test
        fun `subtract slash-32 from slash-0 produces correct cover`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("0.0.0.0/0")),
                exclude = listOf(Cidr.parse("1.2.3.4/32"))
            )
            assertTrue(result.all { it is Cidr.V4 })
            assertFalse(coversAddress(result, Cidr.parse("1.2.3.4/32").networkBytes))
            assertTrue(coversAddress(result, Cidr.parse("1.2.3.3/32").networkBytes))
            assertTrue(coversAddress(result, Cidr.parse("1.2.3.5/32").networkBytes))
            assertTrue(coversAddress(result, byteArrayOf(0, 0, 0, 0)))
            assertTrue(
                coversAddress(
                    result,
                    byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte())
                )
            )
        }

        @Test
        fun `subtract identical block yields empty`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("10.0.0.0/8")),
                exclude = listOf(Cidr.parse("10.0.0.0/8"))
            )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `subtract non-overlapping yields base unchanged`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("10.0.0.0/8")),
                exclude = listOf(Cidr.parse("192.168.0.0/16"))
            )
            assertEquals(listOf(Cidr.parse("10.0.0.0/8")), result)
        }

        @Test
        fun `subtract excludes private ranges from default route`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("0.0.0.0/0")),
                exclude = listOf(
                    Cidr.parse("10.0.0.0/8"),
                    Cidr.parse("172.16.0.0/12"),
                    Cidr.parse("192.168.0.0/16"),
                )
            )
            assertFalse(coversAddress(result, byteArrayOf(10, 5, 6, 7)))
            assertFalse(coversAddress(result, byteArrayOf(172.toByte(), 20, 0, 1)))
            assertFalse(coversAddress(result, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
            assertTrue(coversAddress(result, byteArrayOf(8, 8, 8, 8)))
            assertTrue(coversAddress(result, byteArrayOf(1, 1, 1, 1)))
        }

        @Test
        fun `subtract is independent across families`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("0.0.0.0/0"), Cidr.parse("::/0")),
                exclude = listOf(Cidr.parse("fc00::/7"))
            )
            assertTrue(result.any { it == Cidr.parse("0.0.0.0/0") })
            assertTrue(result.any { it is Cidr.V6 && it != Cidr.parse("::/0") })
        }

        @Test
        fun `subtracting bigger block from smaller yields empty`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("10.5.0.0/16")),
                exclude = listOf(Cidr.parse("10.0.0.0/8"))
            )
            assertTrue(result.isEmpty())
        }

        @Test
        fun `result is coalesced`() {
            val result = CidrMath.subtract(
                base = listOf(Cidr.parse("10.0.0.0/24"), Cidr.parse("10.0.1.0/24")),
                exclude = emptyList(),
            )
            assertEquals(listOf(Cidr.parse("10.0.0.0/23")), result)
        }
    }

    private fun coversAddress(set: List<Cidr>, addr: ByteArray): Boolean {
        val asCidr = when (addr.size) {
            4 -> Cidr.V4(addr, 32)
            16 -> Cidr.V6(addr, 128)
            else -> error("addr must be 4 or 16 bytes")
        }
        return set.any { CidrMath.contains(it, asCidr) }
    }
}
