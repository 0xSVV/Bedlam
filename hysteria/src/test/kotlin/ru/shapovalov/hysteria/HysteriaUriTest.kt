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

    @Test
    fun `insecure=1 sets tlsInsecure`() {
        val r = parseHysteriaUri("hysteria2://token@host.example/?insecure=1")
        assertEquals(true, r.config.tls.tlsInsecure)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "true", "yes", ""])
    fun `insecure=anything_else stays false`(value: String) {
        val r = parseHysteriaUri("hysteria2://token@host.example/?insecure=$value")
        assertEquals(false, r.config.tls.tlsInsecure)
    }

    @Test
    fun `pinSHA256 query maps to TLS`() {
        val r = parseHysteriaUri("hysteria2://t@h/?pinSHA256=abc123")
        assertEquals("abc123", r.config.tls.tlsPinSHA256)
    }

    @Test
    fun `obfs query maps to obfuscation`() {
        val r = parseHysteriaUri("hysteria2://t@h/?obfs=salamander&obfs-password=p%20w")
        val o = r.config.obfuscation!!
        assertEquals("salamander", o.obfuscationType)
        assertEquals("p w", o.obfuscationPassword)
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
