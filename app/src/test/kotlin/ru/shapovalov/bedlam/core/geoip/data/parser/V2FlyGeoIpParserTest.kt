@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ru.shapovalov.bedlam.core.geoip.data.parser

import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.CountryCode

class V2FlyGeoIpParserTest {

    private val parser = V2FlyGeoIpParser()
    private val protoBuf = ProtoBuf {}

    @Test
    fun `parses encoded v4 cidrs back to V4`() {
        val fixture = GeoIpListProto(
            entry = listOf(
                GeoIpProto(
                    countryCode = "RU",
                    cidr = listOf(
                        CidrProto(ip = byteArrayOf(5, 0, 0, 0), prefix = 8),
                        CidrProto(ip = byteArrayOf(31, 0, 0, 0), prefix = 16),
                    ),
                ),
            ),
        )
        val bytes = protoBuf.encodeToByteArray(GeoIpListProto.serializer(), fixture)

        val result = parser.parse(bytes)

        val ru = result[CountryCode.of("RU")]
        assertNotNull(ru)
        assertEquals(2, ru!!.size)
        assertTrue(ru.contains(Cidr.parse("5.0.0.0/8")))
        assertTrue(ru.contains(Cidr.parse("31.0.0.0/16")))
    }

    @Test
    fun `parses v6 cidrs back to V6`() {
        val v6Bytes = ByteArray(16).apply {
            this[0] = 0x20
            this[1] = 0x01
            this[2] = 0x0d
            this[3] = 0xb8.toByte()
        }
        val fixture = GeoIpListProto(
            entry = listOf(
                GeoIpProto(
                    countryCode = "CN",
                    cidr = listOf(CidrProto(ip = v6Bytes, prefix = 32)),
                ),
            ),
        )
        val bytes = protoBuf.encodeToByteArray(GeoIpListProto.serializer(), fixture)

        val result = parser.parse(bytes)

        val cn = result[CountryCode.of("CN")]
        assertNotNull(cn)
        assertEquals(1, cn!!.size)
        assertTrue(cn.first() is Cidr.V6)
        assertEquals(32, cn.first().prefixLength)
    }

    @Test
    fun `country codes are normalized to upper case`() {
        val fixture = GeoIpListProto(
            entry = listOf(
                GeoIpProto(
                    countryCode = "ru",
                    cidr = listOf(CidrProto(ip = byteArrayOf(1, 2, 3, 0), prefix = 24)),
                ),
            ),
        )
        val bytes = protoBuf.encodeToByteArray(GeoIpListProto.serializer(), fixture)

        val result = parser.parse(bytes)

        assertNotNull(result[CountryCode.of("RU")])
    }

    @Test
    fun `malformed entries are silently dropped`() {
        val fixture = GeoIpListProto(
            entry = listOf(
                GeoIpProto(
                    countryCode = "ZZ",
                    cidr = listOf(
                        CidrProto(ip = byteArrayOf(1, 2), prefix = 24), // wrong byte length
                        CidrProto(ip = byteArrayOf(1, 2, 3, 4), prefix = 99), // prefix out of range
                    ),
                ),
                GeoIpProto(
                    countryCode = "BAD",   // 3-letter code rejected by CountryCode
                    cidr = listOf(CidrProto(ip = byteArrayOf(5, 0, 0, 0), prefix = 8)),
                ),
                GeoIpProto(
                    countryCode = "RU",
                    cidr = listOf(CidrProto(ip = byteArrayOf(5, 0, 0, 0), prefix = 8)),
                ),
            ),
        )
        val bytes = protoBuf.encodeToByteArray(GeoIpListProto.serializer(), fixture)

        val result = parser.parse(bytes)

        // ZZ had two bad cidrs and no good ones — skipped entirely.
        assertNull(result[CountryCode.of("ZZ")])
        // BAD isn't a valid ISO code — skipped.
        assertEquals(1, result.size)
        assertNotNull(result[CountryCode.of("RU")])
    }

    @Test
    fun `empty file parses to empty map`() {
        val empty = protoBuf.encodeToByteArray(
            GeoIpListProto.serializer(),
            GeoIpListProto(entry = emptyList()),
        )
        assertTrue(parser.parse(empty).isEmpty())
    }
}
