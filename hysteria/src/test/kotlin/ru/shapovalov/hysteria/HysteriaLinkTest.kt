package ru.shapovalov.hysteria

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.shapovalov.hysteria.config.BandwidthOptions
import ru.shapovalov.hysteria.config.BehaviorOptions
import ru.shapovalov.hysteria.config.CongestionOptions
import ru.shapovalov.hysteria.config.HysteriaConfig
import ru.shapovalov.hysteria.config.ObfuscationOptions
import ru.shapovalov.hysteria.config.QuicOptions
import ru.shapovalov.hysteria.config.RealmOptions
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import ru.shapovalov.hysteria.config.TransportOptions

class HysteriaLinkTest {

    private fun config(
        address: String = "host.example:443",
        auth: String = "secret",
        tls: TlsOptions = TlsOptions(tlsSni = "host.example"),
        obfuscation: ObfuscationOptions = ObfuscationOptions(),
    ) = HysteriaConfig(
        server = ServerCredentials(address = address, auth = auth),
        tls = tls,
        obfuscation = obfuscation,
    )

    private fun assertRoundTrip(config: HysteriaConfig, name: String) {
        val link = buildHysteriaLink(config, name)
        val parsed = parseHysteriaUri(link.uri!!)
        assertEquals(config, parsed.config, link.uri)
        assertEquals(name, parsed.name, link.uri)
    }

    @Test
    fun `builds a plain link with the path and name`() {
        val link = buildHysteriaLink(config(), "Home")

        assertEquals("hysteria2://secret@host.example:443/#Home", link.uri)
    }

    @Test
    fun `leaves out the userinfo when there is no auth`() {
        val link = buildHysteriaLink(config(auth = ""), "")

        assertEquals("hysteria2://host.example:443/", link.uri)
    }

    @Test
    fun `encodes colons in the auth so clients reading only the username get all of it`() {
        val link = buildHysteriaLink(config(auth = "user:pass"), "")

        assertEquals("hysteria2://user%3Apass@host.example:443/", link.uri)
    }

    @Test
    fun `percent-encodes reserved characters in the auth`() {
        val link = buildHysteriaLink(config(auth = "a+b @/?#%"), "")

        assertEquals("hysteria2://a%2Bb%20%40%2F%3F%23%25@host.example:443/", link.uri)
    }

    @Test
    fun `encodes spaces in the name as %20 and emoji as UTF-8`() {
        val link = buildHysteriaLink(config(), "🚀 Fast")

        assertEquals("hysteria2://secret@host.example:443/#%F0%9F%9A%80%20Fast", link.uri)
    }

    @Test
    fun `keeps an IPv6 host in brackets`() {
        val link = buildHysteriaLink(config(address = "[2001:db8::1]:8443", tls = TlsOptions()), "")

        assertEquals("hysteria2://secret@[2001:db8::1]:8443/", link.uri)
    }

    @Test
    fun `brackets a bare IPv6 host`() {
        val link = buildHysteriaLink(config(address = "2001:db8::1", tls = TlsOptions()), "")

        assertEquals("hysteria2://secret@[2001:db8::1]/", link.uri)
    }

    @Test
    fun `a port-hopping profile uses its first port and carries the full spec as mport`() {
        val link = buildHysteriaLink(config(address = "host.example:20000-30000,40000"), "")

        assertEquals("hysteria2://secret@host.example:20000/?mport=20000-30000,40000", link.uri)
    }

    @Test
    fun `adds sni only when it differs from the host`() {
        val same = buildHysteriaLink(config(tls = TlsOptions(tlsSni = "host.example")), "")
        val other = buildHysteriaLink(config(tls = TlsOptions(tlsSni = "cdn.example")), "")
        val unset = buildHysteriaLink(config(tls = TlsOptions()), "")

        assertEquals("hysteria2://secret@host.example:443/", same.uri)
        assertEquals("hysteria2://secret@host.example:443/?sni=cdn.example", other.uri)
        assertEquals("hysteria2://secret@host.example:443/", unset.uri)
    }

    @Test
    fun `adds insecure only when it is on`() {
        val on = buildHysteriaLink(config(tls = TlsOptions(tlsSni = "host.example", tlsInsecure = true)), "")
        val off = buildHysteriaLink(config(), "")

        assertEquals("hysteria2://secret@host.example:443/?insecure=1", on.uri)
        assertEquals("hysteria2://secret@host.example:443/", off.uri)
    }

    @Test
    fun `normalizes the pin as upstream does`() {
        val tls = TlsOptions(tlsSni = "host.example", tlsPinSHA256 = "AB:CD-EF:01")
        val link = buildHysteriaLink(config(tls = tls), "")

        assertEquals("hysteria2://secret@host.example:443/?pinSHA256=abcdef01", link.uri)
    }

    @ParameterizedTest
    @ValueSource(strings = ["salamander", "gecko"])
    fun `carries the obfuscation type and password`(type: String) {
        val link = buildHysteriaLink(
            config(obfuscation = ObfuscationOptions(obfuscationType = type, obfuscationPassword = "p w+&")),
            "",
        )

        assertEquals("hysteria2://secret@host.example:443/?obfs=$type&obfs-password=p%20w%2B%26", link.uri)
    }

    @Test
    fun `sorts the query keys the way url Values Encode does`() {
        val link = buildHysteriaLink(
            config(
                address = "host.example:5000-6000",
                tls = TlsOptions(
                    tlsSni = "cdn.example",
                    tlsInsecure = true,
                    tlsPinSHA256 = "ab",
                    ech = "AEj+DQBA",
                ),
                obfuscation = ObfuscationOptions("salamander", "pw"),
            ),
            "",
        )

        assertEquals(
            "hysteria2://secret@host.example:5000/?ech=AEj%2BDQBA&insecure=1&mport=5000-6000" +
                "&obfs=salamander&obfs-password=pw&pinSHA256=ab&sni=cdn.example",
            link.uri,
        )
    }

    @Test
    fun `turns a PEM ECH config into the base64 list`() {
        val pem = "-----BEGIN ECH CONFIGS-----\nAEj+DQBA\nAAEC\n-----END ECH CONFIGS-----\n"
        val link = buildHysteriaLink(config(tls = TlsOptions(tlsSni = "host.example", ech = pem)), "")

        assertEquals("hysteria2://secret@host.example:443/?ech=AEj%2BDQBAAAEC", link.uri)
    }

    @Test
    fun `round-trips a plain profile`() {
        assertRoundTrip(config(), "Home")
    }

    @Test
    fun `round-trips an IPv6 profile`() {
        assertRoundTrip(config(address = "[2001:db8::1]:8443", tls = TlsOptions()), "v6")
    }

    @Test
    fun `round-trips a port-hopping profile`() {
        assertRoundTrip(config(address = "host.example:20000-30000,40000,41000-41010"), "Hop")
    }

    @Test
    fun `round-trips a pinned insecure profile`() {
        val tls = TlsOptions(tlsSni = "cdn.example", tlsInsecure = true, tlsPinSHA256 = "0a1b2c")
        assertRoundTrip(config(address = "203.0.113.5:443", tls = tls), "Pinned")
    }

    @ParameterizedTest
    @ValueSource(strings = ["salamander", "gecko"])
    fun `round-trips an obfuscated profile`(type: String) {
        val obfuscation = ObfuscationOptions(obfuscationType = type, obfuscationPassword = "o+b f/s?#%&=")
        assertRoundTrip(config(obfuscation = obfuscation), type)
    }

    @Test
    fun `round-trips an ECH profile`() {
        assertRoundTrip(config(tls = TlsOptions(tlsSni = "host.example", ech = "AEj+DQBA/w==")), "ECH")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a+b", "user:pass", "a@b", "a/b", "a?b", "a#b", "100%", "with space",
            "🚀 rocket", "+:@/?#% 😀",
        ],
    )
    fun `round-trips auth and names with reserved characters`(text: String) {
        assertRoundTrip(config(auth = text), text)
    }

    @Test
    fun `a plain profile has nothing to report`() {
        val link = buildHysteriaLink(config(), "")

        assertEquals(emptySet<LinkConnectionGap>(), link.connectionGaps)
        assertEquals(emptySet<LinkTuningGap>(), link.tuningGaps)
        assertFalse(link.insecureIgnoresPinElsewhere)
    }

    @Test
    fun `reports the certificate settings a link cannot carry`() {
        val tls = TlsOptions(
            tlsSni = "host.example",
            tlsCa = "/ca.pem",
            tlsClientCert = "/client.pem",
            tlsClientKey = "/client.key",
        )
        val link = buildHysteriaLink(config(tls = tls), "")

        assertEquals(
            setOf(LinkConnectionGap.CustomCa, LinkConnectionGap.ClientCertificate),
            link.connectionGaps,
        )
    }

    @Test
    fun `a realm profile has no link`() {
        val realm = config(address = "realm://rendezvous.example/home").copy(realm = RealmOptions())
        val link = buildHysteriaLink(realm, "Realm")

        assertNull(link.uri)
        assertEquals(setOf(LinkConnectionGap.Realm), link.connectionGaps)
    }

    @Test
    fun `reports the tuning a link leaves out`() {
        val tuned = config(obfuscation = ObfuscationOptions("gecko", "pw", geckoMinPacketSize = 600)).copy(
            quic = QuicOptions(maxIdleTimeoutSec = 60),
            congestion = CongestionOptions(congestionType = "bbr"),
            bandwidth = BandwidthOptions(maxTxMbps = 50),
            transport = TransportOptions(hopIntervalSec = 30),
        )
        val link = buildHysteriaLink(tuned, "")

        assertEquals(
            setOf(
                LinkTuningGap.Quic,
                LinkTuningGap.Congestion,
                LinkTuningGap.Bandwidth,
                LinkTuningGap.HopInterval,
                LinkTuningGap.ObfuscationPacketSize,
            ),
            link.tuningGaps,
        )
    }

    @Test
    fun `default tuning objects are not reported`() {
        val defaults = config().copy(
            quic = QuicOptions(),
            congestion = CongestionOptions(),
            bandwidth = BandwidthOptions(),
            transport = TransportOptions(),
            behavior = BehaviorOptions(),
        )

        assertEquals(emptySet<LinkTuningGap>(), buildHysteriaLink(defaults, "").tuningGaps)
    }

    @Test
    fun `flags insecure together with a pin`() {
        val both = TlsOptions(tlsSni = "host.example", tlsInsecure = true, tlsPinSHA256 = "ab")
        val pinOnly = TlsOptions(tlsSni = "host.example", tlsPinSHA256 = "ab")

        assertTrue(buildHysteriaLink(config(tls = both), "").insecureIgnoresPinElsewhere)
        assertFalse(buildHysteriaLink(config(tls = pinOnly), "").insecureIgnoresPinElsewhere)
    }
}
