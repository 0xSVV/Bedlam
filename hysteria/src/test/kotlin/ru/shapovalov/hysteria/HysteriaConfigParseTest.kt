package ru.shapovalov.hysteria

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.hysteria.config.HysteriaConfig

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

        val parsed = parseHysteriaConfig(json)

        assertEquals(original, parsed.config)
        assertEquals("", parsed.name)
    }

    @Test
    fun `parses config json with surrounding whitespace`() {
        val original = sampleConfig()
        val json = "  \n" + clipboardJson.encodeToString(HysteriaConfig.serializer(), original) + "\n  "

        assertEquals(original, parseHysteriaConfig(json).config)
    }

    @Test
    fun `ignores unknown keys in config json`() {
        val original = sampleConfig()
        val json = clipboardJson.encodeToString(HysteriaConfig.serializer(), original)
            .replaceFirst("{", "{\n  \"futureField\": \"x\",")

        assertEquals(original, parseHysteriaConfig(json).config)
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

        val parsed = parseHysteriaConfig(legacy)

        assertEquals("", parsed.config.tls.ech)
        assertEquals("host.example:443", parsed.config.server.address)
    }

    @Test
    fun `still parses a hysteria uri`() {
        val parsed = parseHysteriaConfig("hysteria2://token@host.example/#My Profile")

        assertEquals("host.example:443", parsed.config.server.address)
        assertEquals("My Profile", parsed.name)
    }
}
