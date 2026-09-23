package ru.shapovalov.hysteria

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HysteriaUriTest {

    @Test
    fun `parses minimal hysteria2 URI with default port`() {
        val r = parseHysteriaUri("hysteria2://token@host.example/")
        assertEquals("host.example:443", r.config.server.address)
        assertEquals("token", r.config.server.auth)
        assertEquals("host.example", r.config.tls.tlsSni)
        assertEquals("", r.name)
    }

    @Test
    fun `accepts hy2 alias`() {
        val r = parseHysteriaUri("hy2://token@host.example:1234/")
        assertEquals("host.example:1234", r.config.server.address)
    }

    @Test
    fun `trims surrounding whitespace`() {
        val r = parseHysteriaUri("   hysteria2://token@host.example/   ")
        assertEquals("host.example:443", r.config.server.address)
    }

    @Test
    fun `decodes percent-encoded auth`() {
        val r = parseHysteriaUri("hysteria2://my%3Apassword@host.example/")
        assertEquals("my:password", r.config.server.auth)
    }

    @Test
    fun `keeps empty auth when userinfo absent`() {
        val r = parseHysteriaUri("hysteria2://host.example/")
        assertEquals("", r.config.server.auth)
    }

    @Test
    fun `keeps a plus sign in the auth`() {
        val r = parseHysteriaUri("hysteria2://abc+def@host.example/")
        assertEquals("abc+def", r.config.server.auth)
    }

    @Test
    fun `keeps a plus sign in the name`() {
        val r = parseHysteriaUri("hysteria2://t@host.example/#Home+Office")
        assertEquals("Home+Office", r.name)
    }

    @Test
    fun `decodes UTF-8 escapes in the name`() {
        val r = parseHysteriaUri("hysteria2://t@host.example/#%F0%9F%9A%80%20Fast")
        assertEquals("🚀 Fast", r.name)
    }

    @Test
    fun `turns a plus sign in a query value into a space as Go does`() {
        val r = parseHysteriaUri("hysteria2://t@host.example/?obfs=salamander&obfs-password=p+w%2B")
        assertEquals("p w+", r.config.obfuscation!!.obfuscationPassword)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "hysteria2://ab%zz@host.example/",
            "hysteria2://ab%4@host.example/",
            "hysteria2://t@host.example/#name%",
            "hysteria2://t@host.example/?sni=a%g1",
        ],
    )
    fun `rejects a malformed percent-escape with a clear message`(link: String) {
        val e = assertThrows(IllegalArgumentException::class.java) { parseHysteriaUri(link) }
        assertEquals("The link has an invalid %-escape", e.message)
    }

    @Test
    fun `uses last at-sign to split userinfo`() {
        val r = parseHysteriaUri("hysteria2://a@b@host.example/")
        assertEquals("a@b", r.config.server.auth)
        assertEquals("host.example:443", r.config.server.address)
    }

    @Test
    fun `decodes percent-encoded fragment as name`() {
        val r = parseHysteriaUri("hysteria2://token@host.example/#My%20Profile")
        assertEquals("My Profile", r.name)
    }

    @Test
    fun `sni defaults to hostname for DNS names`() {
        val r = parseHysteriaUri("hysteria2://token@host.example/")
        assertEquals("host.example", r.config.tls.tlsSni)
    }

    @Test
    fun `sni stays empty for bare IPv4`() {
        val r = parseHysteriaUri("hysteria2://token@1.2.3.4/")
        assertEquals("", r.config.tls.tlsSni)
    }

    @Test
    fun `sni stays empty for bracketed IPv6`() {
        val r = parseHysteriaUri("hysteria2://token@[2001:db8::1]:443/")
        assertEquals("", r.config.tls.tlsSni)
    }

    @Test
    fun `query overrides sni`() {
        val r = parseHysteriaUri("hysteria2://token@host.example/?sni=other.example")
        assertEquals("other.example", r.config.tls.tlsSni)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1", "t", "T", "TRUE", "true", "True"])
    fun `insecure reads every spelling Go parses as true`(value: String) {
        val r = parseHysteriaUri("hysteria2://token@host.example/?insecure=$value")
        assertEquals(true, r.config.tls.tlsInsecure)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "f", "F", "FALSE", "false", "False", "yes", "on", ""])
    fun `insecure stays false for false spellings and values Go rejects`(value: String) {
        val r = parseHysteriaUri("hysteria2://token@host.example/?insecure=$value")
        assertEquals(false, r.config.tls.tlsInsecure)
    }

    @Test
    fun `pinSHA256 query maps to TLS`() {
        val r = parseHysteriaUri("hysteria2://t@h/?pinSHA256=abc123")
        assertEquals("abc123", r.config.tls.tlsPinSHA256)
    }

    @Test
    fun `ech query maps to TLS with URL decoding`() {
        val r = parseHysteriaUri("hysteria2://t@h/?ech=AEj%2BDQBA%3D%3D")
        assertEquals("AEj+DQBA==", r.config.tls.ech)
    }

    @Test
    fun `absent ech query yields empty ech`() {
        val r = parseHysteriaUri("hysteria2://t@h/")
        assertEquals("", r.config.tls.ech)
    }

    @Test
    fun `obfs query maps to obfuscation`() {
        val r = parseHysteriaUri("hysteria2://t@h/?obfs=salamander&obfs-password=p%20w")
        val o = r.config.obfuscation!!
        assertEquals("salamander", o.obfuscationType)
        assertEquals("p w", o.obfuscationPassword)
    }

    @Test
    fun `gecko obfs maps through with default packet sizes`() {
        val r = parseHysteriaUri("hysteria2://t@h/?obfs=gecko&obfs-password=pw")
        val o = r.config.obfuscation!!
        assertEquals("gecko", o.obfuscationType)
        assertEquals("pw", o.obfuscationPassword)
        assertEquals(0, o.geckoMinPacketSize)
        assertEquals(0, o.geckoMaxPacketSize)
    }

    @Test
    fun `bracketed IPv6 host renders bracketed in server address`() {
        val r = parseHysteriaUri("hysteria2://t@[2001:db8::1]:8443/")
        assertEquals("[2001:db8::1]:8443", r.config.server.address)
    }

    @Test
    fun `port-hopping range survives intact`() {
        val r = parseHysteriaUri("hysteria2://t@host.example:8000-9000/")
        assertEquals("host.example:8000-9000", r.config.server.address)
    }

    @Test
    fun `port-hopping list survives intact`() {
        val r = parseHysteriaUri("hysteria2://t@host.example:8000,8100,8200/")
        assertEquals("host.example:8000,8100,8200", r.config.server.address)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "0", "65536", ""])
    fun `rejects invalid port`(port: String) {
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteriaUri("hysteria2://t@host.example:$port/")
        }
    }

    @Test
    fun `query without leading slash still parses`() {
        val r = parseHysteriaUri("hysteria2://t@h.example?sni=foo")
        assertEquals("foo", r.config.tls.tlsSni)
    }

    @Test
    fun `fragment without query still parses`() {
        val r = parseHysteriaUri("hysteria2://t@h.example#alpha")
        assertEquals("alpha", r.name)
    }

    @Test
    fun `rejects missing scheme separator`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteriaUri("hysteria2:host.example")
        }
    }

    @Test
    fun `rejects unknown scheme`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteriaUri("http://host.example/")
        }
    }

    @Test
    fun `rejects empty hostname`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteriaUri("hysteria2://token@:443/")
        }
    }

    @Test
    fun `rejects unclosed bracketed IPv6`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteriaUri("hysteria2://token@[2001:db8::1/")
        }
    }
}
