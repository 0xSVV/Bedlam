package ru.shapovalov.hysteria

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.defaultBandwidthOptions
import ru.shapovalov.hysteria.config.defaultBehaviorOptions
import ru.shapovalov.hysteria.config.defaultCongestionOptions
import ru.shapovalov.hysteria.config.defaultQuicOptions
import ru.shapovalov.hysteria.config.defaultTlsOptions
import ru.shapovalov.hysteria.config.defaultTransportOptions

class HysteriaConfigParseTest {

    private val clipboardJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private fun sampleConfig(): HysteriaConfig =
        parseHysteriaUri(
            "hysteria2://token@host.example:8443/" +
                "?sni=foo&insecure=1&obfs=salamander&obfs-password=pw&ech=AAT%2BDQAA",
        ).config

    @Test
    fun `round-trips a config copied from the app clipboard`() {
        val original = sampleConfig()
        val json = clipboardJson.encodeToString(HysteriaConfig.serializer(), original)

        val parsed = parseHysteriaJson(json)

        assertEquals(original, parsed.config)
        assertEquals("", parsed.name)
    }

    @Test
    fun `parses config json with surrounding whitespace`() {
        val original = sampleConfig()
        val json = "  \n" + clipboardJson.encodeToString(HysteriaConfig.serializer(), original) + "\n  "

        assertEquals(original, parseHysteriaJson(json).config)
    }

    @Test
    fun `ignores unknown keys in config json`() {
        val original = sampleConfig()
        val json = clipboardJson.encodeToString(HysteriaConfig.serializer(), original)
            .replaceFirst("{", "{\n  \"futureField\": \"x\",")

        assertEquals(original, parseHysteriaJson(json).config)
    }

    @Test
    fun `parses config json persisted before the ech field existed`() {
        val legacy = """
            {
              "server": {"server": "host.example:443", "auth": "token"},
              "tls": {
                "tlsSni": "",
                "tlsInsecure": false,
                "tlsPinSHA256": "",
                "tlsCa": "",
                "tlsClientCert": "",
                "tlsClientKey": ""
              }
            }
        """.trimIndent()

        val parsed = parseHysteriaJson(legacy)

        assertEquals("", parsed.config.tls.ech)
        assertEquals("host.example:443", parsed.config.server.address)
    }

    @Test
    fun `parses config json that predates every optional field`() {
        val legacy = """{"server": {"server": "host.example:443", "auth": "token"}, "tls": {}}"""

        val parsed = parseHysteriaJson(legacy).config

        assertEquals("host.example:443", parsed.server.address)
        assertEquals(defaultTlsOptions, parsed.tls)
    }

    @Test
    fun `a profile saved before the parrot switch existed keeps the parrot off`() {
        val legacy = """
            {
              "server": {"server": "host.example:443", "auth": "token"},
              "tls": {},
              "quic": {"maxIdleTimeoutSec": 30, "disablePathMTUDiscovery": true}
            }
        """.trimIndent()

        val parsed = parseHysteriaJson(legacy).config

        assertEquals(true, parsed.quic?.disableChromeParrot)
        assertEquals(false, parsed.quic?.disableGso)
        assertEquals(30, parsed.quic?.maxIdleTimeoutSec)
    }

    @Test
    fun `applies documented defaults to empty option objects`() {
        val sparse = """
            {
              "server": {"server": "host.example:443", "auth": "token"},
              "tls": {},
              "quic": {},
              "congestion": {},
              "bandwidth": {},
              "transport": {},
              "behavior": {},
              "obfuscation": {}
            }
        """.trimIndent()

        val parsed = parseHysteriaJson(sparse).config

        assertEquals(defaultQuicOptions, parsed.quic)
        assertEquals(defaultCongestionOptions, parsed.congestion)
        assertEquals(defaultBandwidthOptions, parsed.bandwidth)
        assertEquals(defaultTransportOptions, parsed.transport)
        assertEquals(defaultBehaviorOptions, parsed.behavior)
        assertEquals("", parsed.obfuscation?.obfuscationType)
    }

    @Test
    fun `reads an optional top-level name`() {
        val original = sampleConfig()
        val json = clipboardJson.encodeToString(HysteriaConfig.serializer(), original)
            .replaceFirst("{", "{\n  \"name\": \"Home\",")

        val parsed = parseHysteriaJson(json)

        assertEquals(original, parsed.config)
        assertEquals("Home", parsed.name)
    }

    @Test
    fun `explains that an official client config is not a profile`() {
        val official = """
            {
              "server": "host.example:443",
              "auth": "token",
              "tls": {"sni": "foo", "insecure": true}
            }
        """.trimIndent()

        val error = assertThrows<IllegalArgumentException> { parseHysteriaJson(official) }

        assertTrue(error.message!!.contains("Hysteria client config"), error.message)
    }

    @Test
    fun `rejects json that is not an object`() {
        assertThrows<IllegalArgumentException> { parseHysteriaJson("[1, 2, 3]") }
        assertThrows<IllegalArgumentException> { parseHysteriaJson("\"hysteria2://x@y/\"") }
    }

    @Test
    fun `rejects text that is not json`() {
        val error = assertThrows<IllegalArgumentException> {
            parseHysteriaJson("hysteria2://token@host.example/#My Profile")
        }

        assertTrue(error.message!!.startsWith("Not valid JSON"), error.message)
    }

    @Test
    fun `rejects an object without a server`() {
        assertThrows<IllegalArgumentException> { parseHysteriaJson("""{"tls": {}}""") }
    }
}
