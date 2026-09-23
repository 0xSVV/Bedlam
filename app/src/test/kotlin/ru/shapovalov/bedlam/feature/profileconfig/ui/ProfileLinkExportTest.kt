package ru.shapovalov.bedlam.feature.profileconfig.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.testing.testConfig
import ru.shapovalov.hysteria.config.BandwidthOptions
import ru.shapovalov.hysteria.config.QuicOptions
import ru.shapovalov.hysteria.config.ServerCredentials
import ru.shapovalov.hysteria.config.TlsOptions
import ru.shapovalov.hysteria.config.TransportOptions

class ProfileLinkExportTest {

    @Test
    fun `a profile a link carries fully is ready to share`() {
        assertEquals(
            LinkExport.Ready("hysteria2://pw@example.com:443/#Home"),
            planLinkExport(testConfig(), "Home"),
        )
    }

    @Test
    fun `a realm profile cannot be shared as a link`() {
        val realm = testConfig().copy(server = ServerCredentials("realm://rendezvous.example/home", "pw"))

        assertEquals(LinkExport.Unavailable, planLinkExport(realm, "Realm"))
    }

    @Test
    fun `lists what the link leaves out, the pin warning first`() {
        val config = testConfig().copy(
            tls = TlsOptions(
                tlsSni = "example.com",
                tlsInsecure = true,
                tlsPinSHA256 = "ab",
                tlsCa = "/ca.pem",
                tlsClientKey = "/client.key",
            ),
            quic = QuicOptions(keepAlivePeriodSec = 10),
            bandwidth = BandwidthOptions(maxRxMbps = 100),
            transport = TransportOptions(hopIntervalSec = 20),
        )

        assertEquals(
            LinkExport.NeedsConfirmation(
                uri = "hysteria2://pw@example.com:443/?insecure=1&pinSHA256=ab",
                warnings = listOf(
                    LinkWarning.InsecureWithoutPinElsewhere,
                    LinkWarning.CustomCa,
                    LinkWarning.ClientCertificate,
                    LinkWarning.Quic,
                    LinkWarning.Bandwidth,
                    LinkWarning.HopInterval,
                ),
            ),
            planLinkExport(config, ""),
        )
    }
}
