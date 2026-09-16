package ru.shapovalov.bedlam.core.routing.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilter
import ru.shapovalov.bedlam.core.routing.domain.model.Cidr
import ru.shapovalov.bedlam.core.routing.domain.model.DirectRouteSource
import ru.shapovalov.bedlam.core.routing.domain.model.DnsMode
import ru.shapovalov.bedlam.core.routing.domain.model.DnsPresets
import ru.shapovalov.bedlam.core.routing.domain.model.Ipv6Mode
import ru.shapovalov.bedlam.core.routing.domain.model.ResolvedSource
import ru.shapovalov.bedlam.core.routing.domain.model.RoutingConfig
import ru.shapovalov.hysteria.api.DnsTransport
import ru.shapovalov.hysteria.api.DnsUpstream

class RoutePlannerTest {

    private val tunV4 = Cidr.parse("172.19.0.1/30") as Cidr.V4
    private val tunV6 = Cidr.parse("fdfe:dcba:9876::1/126") as Cidr.V6
    private val resolverV4 = "172.19.0.2"
    private val resolverV6 = "fdfe:dcba:9876::2"

    private val cloudflareV4 = listOf("1.1.1.1/32", "1.0.0.1/32").map(Cidr::parse)
    private val cloudflareV6 = listOf("2606:4700:4700::1111/128", "2606:4700:4700::1001/128").map(Cidr::parse)
    private val googleV4 = listOf("8.8.8.8/32", "8.8.4.4/32").map(Cidr::parse)
    private val googleV6 = listOf("2001:4860:4860::8888/128", "2001:4860:4860::8844/128").map(Cidr::parse)

    private class PresetRoutes(
        val mode: DnsMode,
        val endpoints: (DnsTransport) -> List<String>,
        val hostRoutesV4: List<Cidr>,
        val hostRoutesV6: List<Cidr>,
        val directRoutes: List<String>,
        val directNeighbours: List<String>,
    )

    private val presets = listOf(
        PresetRoutes(
            DnsMode.Cloudflare,
            DnsPresets::cloudflare,
            cloudflareV4,
            cloudflareV6,
            listOf("1.1.1.0/24", "1.0.0.0/24", "2606:4700::/32"),
            listOf("1.1.1.2/32", "1.0.0.2/32", "2606:4700:4700::2/128"),
        ),
        PresetRoutes(
            DnsMode.Google,
            DnsPresets::google,
            googleV4,
            googleV6,
            listOf("8.8.8.0/24", "8.8.4.0/24", "2001:4860::/32"),
            listOf("8.8.8.9/32", "8.8.4.5/32", "2001:4860:4860::2/128"),
        ),
    )

    private fun planner(
        supportsExclude: Boolean = true,
        max: Int = RoutePlanner.DEFAULT_MAX_TOTAL_ROUTES,
        systemDns: () -> List<String> = { emptyList() },
    ): RoutePlanner = RoutePlanner(
        supportsExcludeRoute = supportsExclude,
        tunPrefixV4 = tunV4,
        tunPrefixV6 = tunV6,
        resolverV4 = resolverV4,
        resolverV6 = resolverV6,
        maxTotalRoutes = max,
        systemDnsServers = systemDns,
    )

    private fun cidrSource(cidr: String, enabled: Boolean = true): ResolvedSource {
        val parsed = Cidr.parse(cidr)
        return ResolvedSource(
            source = DirectRouteSource.Cidr(cidr, parsed, "", enabled, 0),
            cidrs = listOf(parsed),
            lastResolvedMillis = null,
            lastError = null,
        )
    }

    private fun customDnsConfig(transport: DnsTransport, vararg entries: String): RoutingConfig =
        RoutingConfig(dnsMode = DnsMode.Custom, dnsTransport = transport, customDns = entries.toList())

    private fun asnSource(
        id: String,
        cidrs: List<String>,
        enabled: Boolean = true
    ): ResolvedSource =
        ResolvedSource(
            source = DirectRouteSource.Asn(id, 13335, "", enabled, 0),
            cidrs = cidrs.map(Cidr::parse),
            lastResolvedMillis = 0L,
            lastError = null,
        )

    @Nested
    inner class Api33AndUp {

        @Test
        fun `bypass-LAN on excludes RFC1918 ranges`() {
            val plan = planner().plan(RoutingConfig(bypassLan = true), AppFilter())
            assertTrue(plan.claimedV4.contains(RoutePlanner.IPV4_DEFAULT))
            assertTrue(plan.claimedV6.contains(RoutePlanner.IPV6_DEFAULT))
            assertTrue(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("192.168.0.0/16") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("172.16.0.0/12") })
        }

        @Test
        fun `loopback is never excluded - VpnService rejects it`() {
            val plan = planner().plan(RoutingConfig(bypassLan = true), AppFilter())
            assertFalse(plan.excludedV4.any { it == Cidr.parse("127.0.0.0/8") })
            assertFalse(plan.excludedV6.any { it == Cidr.parse("::1/128") })
        }

        @Test
        fun `bypass-LAN off only excludes the TUN prefix`() {
            val plan = planner().plan(RoutingConfig(bypassLan = false), AppFilter())
            assertFalse(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
            assertTrue(plan.excludedV4.any { CidrMath.contains(it, tunV4) })
        }

        @Test
        fun `ipv6 disabled still claims v6 default as a sink`() {
            val plan = planner().plan(RoutingConfig(ipv6Mode = Ipv6Mode.Disabled), AppFilter())
            assertEquals(listOf(RoutePlanner.IPV6_DEFAULT), plan.claimedV6)
            assertFalse(plan.ipv6Enabled)
        }

        @Test
        fun `ipv6 bypass-only claims no v6 routes`() {
            val plan = planner().plan(RoutingConfig(ipv6Mode = Ipv6Mode.BypassOnly), AppFilter())
            assertTrue(plan.claimedV6.isEmpty())
            assertFalse(plan.ipv6Enabled)
        }

        @Test
        fun `ipv6 disabled advertises only the v4 resolver`() {
            val plan = planner().plan(
                RoutingConfig(ipv6Mode = Ipv6Mode.Disabled, dnsMode = DnsMode.Cloudflare),
                AppFilter(),
            )
            assertEquals(listOf(resolverV4), plan.dnsServers)
            assertTrue(plan.dnsUpstream.servers.contains("[2606:4700:4700::1111]:53"))
        }

        @Test
        fun `dns Cloudflare uses CF addresses behind the on-TUN resolver`() {
            val plan = planner().plan(RoutingConfig(dnsMode = DnsMode.Cloudflare), AppFilter())
            assertEquals(listOf(resolverV4, resolverV6), plan.dnsServers)
            assertEquals(
                DnsUpstream(DnsTransport.Tcp, DnsPresets.cloudflare(DnsTransport.Tcp)),
                plan.dnsUpstream,
            )
        }

        @Test
        fun `dns Google follows the chosen transport`() {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Google, dnsTransport = DnsTransport.Https),
                AppFilter(),
            )
            assertEquals(
                DnsUpstream(DnsTransport.Https, DnsPresets.google(DnsTransport.Https)),
                plan.dnsUpstream,
            )
        }

        @Test
        fun `dns System uses underlying network resolvers`() {
            val plan = planner(systemDns = { listOf("9.9.9.9") })
                .plan(RoutingConfig(dnsMode = DnsMode.System), AppFilter())
            assertEquals(listOf("9.9.9.9:53"), plan.dnsUpstream.servers)
            assertEquals(listOf(resolverV4, resolverV6), plan.dnsServers)
        }

        @Test
        fun `dns System orders v4 before v6 and keeps the transport plain`() {
            val plan = planner(systemDns = { listOf("2001:db8::53", "9.9.9.9") }).plan(
                RoutingConfig(dnsMode = DnsMode.System, dnsTransport = DnsTransport.Https),
                AppFilter(),
            )
            assertEquals(DnsTransport.Tcp, plan.dnsUpstream.transport)
            assertEquals(listOf("9.9.9.9:53", "[2001:db8::53]:53"), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns System keeps udp when chosen`() {
            val plan = planner(systemDns = { listOf("9.9.9.9") }).plan(
                RoutingConfig(dnsMode = DnsMode.System, dnsTransport = DnsTransport.Udp),
                AppFilter(),
            )
            assertEquals(DnsTransport.Udp, plan.dnsUpstream.transport)
        }

        @Test
        fun `dns System falls back to Cloudflare when no public resolvers`() {
            val plan = planner().plan(RoutingConfig(dnsMode = DnsMode.System), AppFilter())
            assertEquals(DnsPresets.cloudflare(DnsTransport.Tcp), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom strips blanks`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    customDns = listOf("9.9.9.9", "  ", "", "  149.112.112.112  ")
                ),
                AppFilter(),
            )
            assertEquals(listOf("9.9.9.9:53", "149.112.112.112:53"), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom drops entries that are not IP addresses`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    customDns = listOf("9.9.9.9", "cloudflare", "1.1.1.1.1", "999.1.1.1"),
                ),
                AppFilter(),
            )
            assertEquals(listOf("9.9.9.9:53"), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom accepts bracketed IPv6 with a port`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Udp,
                    customDns = listOf("[2620:fe::fe]:5353", "2620:fe::9"),
                ),
                AppFilter(),
            )
            assertEquals(listOf("[2620:fe::fe]:5353", "[2620:fe::9]:53"), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom accepts hosts for DoT and expands bare hosts for DoH`() {
            val dot = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Tls,
                    customDns = listOf("dns.quad9.net", "9.9.9.9:8853"),
                ),
                AppFilter(),
            )
            assertEquals(listOf("dns.quad9.net:853", "9.9.9.9:8853"), dot.dnsUpstream.servers)

            val doh = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Https,
                    customDns = listOf("dns.quad9.net", "https://dns.google/dns-query"),
                ),
                AppFilter(),
            )
            assertEquals(
                listOf("https://dns.quad9.net/dns-query", "https://dns.google/dns-query"),
                doh.dnsUpstream.servers,
            )
        }

        @Test
        fun `dns Custom rejects hostnames for UDP and TCP`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Udp,
                    customDns = listOf("dns.quad9.net", "9.9.9.9"),
                ),
                AppFilter(),
            )
            assertEquals(listOf("9.9.9.9:53"), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom falls back to Cloudflare when nothing is usable`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Tls,
                    customDns = listOf("not a host"),
                ),
                AppFilter(),
            )
            assertEquals(DnsPresets.cloudflare(DnsTransport.Tls), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom falls back to Cloudflare when the list is empty`() {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Custom, customDns = emptyList()),
                AppFilter(),
            )
            assertEquals(DnsPresets.cloudflare(DnsTransport.Tcp), plan.dnsUpstream.servers)
        }

        @Test
        fun `dns Custom keeps v6 upstreams when IPv6 is off`() {
            val plan = planner().plan(
                RoutingConfig(
                    ipv6Mode = Ipv6Mode.Disabled,
                    dnsMode = DnsMode.Custom,
                    customDns = listOf("2606:4700:4700::1111"),
                ),
                AppFilter(),
            )
            assertEquals(listOf("[2606:4700:4700::1111]:53"), plan.dnsUpstream.servers)
            assertEquals(listOf(resolverV4), plan.dnsServers)
        }

        @Test
        fun `dns transport is carried into the plan`() {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Cloudflare, dnsTransport = DnsTransport.Tls),
                AppFilter(),
            )
            assertEquals(DnsTransport.Tls, plan.dnsUpstream.transport)
            assertEquals(DnsPresets.cloudflare(DnsTransport.Tls), plan.dnsUpstream.servers)
        }

        @Test
        fun `on-TUN resolver is claimed as a host route`() {
            val plan = planner().plan(RoutingConfig(), AppFilter())
            assertTrue(plan.claimedV4.any { it == Cidr.parse("$resolverV4/32") })
            assertTrue(plan.claimedV6.any { it == Cidr.parse("$resolverV6/128") })

            val disabled = planner().plan(RoutingConfig(ipv6Mode = Ipv6Mode.Disabled), AppFilter())
            assertFalse(disabled.claimedV6.any { it == Cidr.parse("$resolverV6/128") })
            val bypass = planner().plan(RoutingConfig(ipv6Mode = Ipv6Mode.BypassOnly), AppFilter())
            assertFalse(bypass.claimedV6.any { it == Cidr.parse("$resolverV6/128") })
        }

        @Test
        fun `public upstreams stay inside the tunnel when a source excludes them`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    customDns = listOf("9.9.9.9"),
                    sources = listOf(cidrSource("9.9.9.0/24")),
                ),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("9.9.9.0/24") })
            assertTrue(plan.claimedV4.any { it == Cidr.parse("9.9.9.9/32") })
        }

        @Test
        fun `preset upstreams are claimed as host routes`() {
            val plan = planner().plan(RoutingConfig(dnsMode = DnsMode.Cloudflare), AppFilter())
            assertTrue(plan.claimedV4.any { it == Cidr.parse("1.1.1.1/32") })
            assertTrue(plan.claimedV6.any { it == Cidr.parse("2606:4700:4700::1111/128") })
        }

        @Test
        fun `a DoH upstream is claimed by the address in its URL`() {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Cloudflare, dnsTransport = DnsTransport.Https),
                AppFilter(),
            )
            assertTrue(plan.claimedV4.any { it == Cidr.parse("1.1.1.1/32") })
        }

        @Test
        fun `every preset transport claims its provider addresses`() {
            for (preset in presets) {
                for (transport in DnsPresets.supportedTransports(preset.mode)) {
                    val plan = planner().plan(
                        RoutingConfig(dnsMode = preset.mode, dnsTransport = transport),
                        AppFilter(),
                    )
                    val label = "${preset.mode} $transport"
                    assertEquals(DnsUpstream(transport, preset.endpoints(transport)), plan.dnsUpstream, label)
                    assertEquals(
                        listOf(RoutePlanner.IPV4_DEFAULT, Cidr.parse("$resolverV4/32")) + preset.hostRoutesV4,
                        plan.claimedV4,
                        label,
                    )
                    assertEquals(
                        listOf(RoutePlanner.IPV6_DEFAULT, Cidr.parse("$resolverV6/128")) + preset.hostRoutesV6,
                        plan.claimedV6,
                        label,
                    )
                    assertEquals(listOf(resolverV4, resolverV6), plan.dnsServers, label)
                }
            }
        }

        @Test
        fun `a stored dns over quic preset dials over TLS and keeps its routes`() {
            for (preset in presets) {
                assertFalse(DnsTransport.Doq in DnsPresets.supportedTransports(preset.mode), "${preset.mode}")
                val plan = planner().plan(
                    RoutingConfig(dnsMode = preset.mode, dnsTransport = DnsTransport.Doq),
                    AppFilter(),
                )
                assertEquals(
                    DnsUpstream(DnsTransport.Tls, preset.endpoints(DnsTransport.Tls)),
                    plan.dnsUpstream,
                    "${preset.mode}",
                )
                assertTrue(plan.claimedV4.containsAll(preset.hostRoutesV4), "${preset.mode}")
                assertTrue(plan.claimedV6.containsAll(preset.hostRoutesV6), "${preset.mode}")
            }
        }

        @Test
        fun `preset routes survive an overlapping direct route`() {
            for (preset in presets) {
                for (transport in DnsPresets.supportedTransports(preset.mode)) {
                    val plan = planner().plan(
                        RoutingConfig(
                            dnsMode = preset.mode,
                            dnsTransport = transport,
                            sources = listOf(asnSource("provider", preset.directRoutes)),
                        ),
                        AppFilter(),
                    )
                    val label = "${preset.mode} $transport"
                    val excluded = plan.excludedV4 + plan.excludedV6
                    assertTrue(excluded.containsAll(preset.directRoutes.map(Cidr::parse)), label)
                    assertTrue(plan.claimedV4.containsAll(preset.hostRoutesV4), label)
                    assertTrue(plan.claimedV6.containsAll(preset.hostRoutesV6), label)
                }
            }
        }

        @Test
        fun `preset routes follow the ipv6 mode`() {
            for (preset in presets) {
                for (transport in DnsPresets.supportedTransports(preset.mode)) {
                    val label = "${preset.mode} $transport"
                    fun planFor(ipv6Mode: Ipv6Mode) = planner().plan(
                        RoutingConfig(ipv6Mode = ipv6Mode, dnsMode = preset.mode, dnsTransport = transport),
                        AppFilter(),
                    )
                    val resolverRouteV4 = listOf(Cidr.parse("$resolverV4/32"))

                    val enabled = planFor(Ipv6Mode.Enabled)
                    assertEquals(resolverRouteV4 + preset.hostRoutesV4, enabled.claimedV4.drop(1), label)
                    assertEquals(
                        listOf(Cidr.parse("$resolverV6/128")) + preset.hostRoutesV6,
                        enabled.claimedV6.drop(1),
                        label,
                    )
                    assertEquals(listOf(resolverV4, resolverV6), enabled.dnsServers, label)

                    val disabled = planFor(Ipv6Mode.Disabled)
                    assertEquals(resolverRouteV4 + preset.hostRoutesV4, disabled.claimedV4.drop(1), label)
                    assertEquals(listOf(RoutePlanner.IPV6_DEFAULT), disabled.claimedV6, label)
                    assertEquals(listOf(resolverV4), disabled.dnsServers, label)

                    val bypass = planFor(Ipv6Mode.BypassOnly)
                    assertEquals(resolverRouteV4 + preset.hostRoutesV4, bypass.claimedV4.drop(1), label)
                    assertTrue(bypass.claimedV6.isEmpty(), label)
                    assertEquals(listOf(resolverV4), bypass.dnsServers, label)
                }
            }
        }

        @Test
        fun `the Cloudflare fallback claims its provider addresses`() {
            val unusable = listOf(
                RoutingConfig(dnsMode = DnsMode.System),
                customDnsConfig(DnsTransport.Tcp),
                customDnsConfig(DnsTransport.Tls, "not a host"),
                customDnsConfig(DnsTransport.Https, "1.1.1.1:53"),
                customDnsConfig(DnsTransport.Http3, "192.168.1.10"),
                customDnsConfig(DnsTransport.Doq, "nope!"),
            ).map { planner().plan(it, AppFilter()) }
            assertEquals(
                listOf(
                    DnsTransport.Tcp,
                    DnsTransport.Tcp,
                    DnsTransport.Tls,
                    DnsTransport.Https,
                    DnsTransport.Http3,
                    DnsTransport.Tls,
                ),
                unusable.map { it.dnsUpstream.transport },
            )
            for (plan in unusable) {
                val label = "${plan.dnsUpstream}"
                assertEquals(
                    DnsPresets.cloudflare(plan.dnsUpstream.transport),
                    plan.dnsUpstream.servers,
                    label,
                )
                assertTrue(plan.claimedV4.containsAll(cloudflareV4), label)
                assertTrue(plan.claimedV6.containsAll(cloudflareV6), label)
            }
        }

        @Test
        fun `custom literal entries claim only their own addresses`() {
            val dot = planner().plan(
                customDnsConfig(DnsTransport.Tls, "9.9.9.9", "dns.quad9.net", "[2620:fe::fe]:853"),
                AppFilter(),
            )
            assertEquals(
                listOf("9.9.9.9:853", "dns.quad9.net:853", "[2620:fe::fe]:853"),
                dot.dnsUpstream.servers,
            )
            assertEquals(
                listOf(Cidr.parse("$resolverV4/32"), Cidr.parse("9.9.9.9/32")),
                dot.claimedV4.drop(1),
            )
            assertEquals(
                listOf(Cidr.parse("$resolverV6/128"), Cidr.parse("2620:fe::fe/128")),
                dot.claimedV6.drop(1),
            )

            val doh = planner().plan(customDnsConfig(DnsTransport.Https, "https://1.1.1.1/dns-query"), AppFilter())
            assertEquals(listOf("https://1.1.1.1/dns-query"), doh.dnsUpstream.servers)
            assertEquals(
                listOf(Cidr.parse("$resolverV4/32"), Cidr.parse("1.1.1.1/32")),
                doh.claimedV4.drop(1),
            )
            assertEquals(listOf(Cidr.parse("$resolverV6/128")), doh.claimedV6.drop(1))
        }

        @Test
        fun `custom hostnames claim no routes even when they name a preset host`() {
            val entries = listOf(
                DnsTransport.Tls to "one.one.one.one:853",
                DnsTransport.Tls to "dns.google:853",
                DnsTransport.Https to "https://cloudflare-dns.com/dns-query",
                DnsTransport.Https to "https://dns.google/dns-query",
            )
            for ((transport, server) in entries) {
                val plan = planner().plan(customDnsConfig(transport, server), AppFilter())
                assertEquals(DnsUpstream(transport, listOf(server)), plan.dnsUpstream, server)
                assertEquals(listOf(Cidr.parse("$resolverV4/32")), plan.claimedV4.drop(1), server)
                assertEquals(listOf(Cidr.parse("$resolverV6/128")), plan.claimedV6.drop(1), server)
            }
        }

        @Test
        fun `a custom filtering resolver URL is kept as entered`() {
            val filtering = "https://security.cloudflare-dns.com/dns-query"
            for (transport in listOf(DnsTransport.Https, DnsTransport.Http3)) {
                val plan = planner().plan(customDnsConfig(transport, filtering), AppFilter())
                assertEquals(DnsUpstream(transport, listOf(filtering)), plan.dnsUpstream, "$transport")
                assertEquals(listOf(Cidr.parse("$resolverV4/32")), plan.claimedV4.drop(1), "$transport")
                assertEquals(listOf(Cidr.parse("$resolverV6/128")), plan.claimedV6.drop(1), "$transport")
            }
        }

        @Test
        fun `a named upstream claims no route`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Tls,
                    customDns = listOf("dns.quad9.net"),
                ),
                AppFilter(),
            )
            assertEquals(listOf(Cidr.parse("$resolverV4/32")), plan.claimedV4.drop(1))
        }

        @Test
        fun `a LAN resolver is advertised directly and never tunneled`() {
            val plan = planner().plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    customDns = listOf("192.168.1.10", "9.9.9.9"),
                ),
                AppFilter(),
            )
            assertTrue(plan.dnsServers.contains("192.168.1.10"))
            assertFalse(plan.dnsUpstream.servers.any { it.startsWith("192.168.1.10") })
            assertEquals(listOf("9.9.9.9:53"), plan.dnsUpstream.servers)
            assertFalse(plan.claimedV4.any { it == Cidr.parse("192.168.1.10/32") })
        }

        @Test
        fun `a LAN-only custom list still advertises the LAN resolver`() {
            val plan = planner().plan(
                RoutingConfig(dnsMode = DnsMode.Custom, customDns = listOf("192.168.1.10")),
                AppFilter(),
            )
            assertTrue(plan.dnsServers.contains("192.168.1.10"))
            assertEquals(DnsPresets.cloudflare(DnsTransport.Tcp), plan.dnsUpstream.servers)
        }

        @Test
        fun `cidr source appears as exclusion`() {
            val plan = planner().plan(
                RoutingConfig(sources = listOf(cidrSource("1.2.3.0/24"))),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("1.2.3.0/24") })
        }

        @Test
        fun `asn source CIDRs propagate to excluded set`() {
            val plan = planner().plan(
                RoutingConfig(
                    sources = listOf(
                        asnSource(
                            "asn1",
                            listOf("5.45.192.0/18", "77.88.0.0/18")
                        )
                    )
                ),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == Cidr.parse("5.45.192.0/18") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("77.88.0.0/18") })
        }

        @Test
        fun `disabled source is ignored`() {
            val plan = planner().plan(
                RoutingConfig(sources = listOf(cidrSource("1.2.3.0/24", enabled = false))),
                AppFilter(),
            )
            assertFalse(plan.excludedV4.any { it == Cidr.parse("1.2.3.0/24") })
        }

        @Test
        fun `huge source set is dropped, LAN remains`() {
            val flood = (0 until 200).map {
                "11.${(it shr 8) and 0xFF}.${it and 0xFF}.0/24"
            }
            val plan = planner(max = 50).plan(
                RoutingConfig(sources = listOf(asnSource("a", flood))),
                AppFilter(),
            )
            assertFalse(plan.excludedV4.any { it == Cidr.parse("11.0.0.0/24") })
            assertTrue(plan.excludedV4.any { it == Cidr.parse("10.0.0.0/8") })
        }

        @Test
        fun `TUN prefix is always excluded`() {
            val plan = planner().plan(
                RoutingConfig(bypassLan = false, ipv6Mode = Ipv6Mode.Enabled),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.any { it == tunV4 })
            assertTrue(plan.excludedV6.any { it == tunV6 })
        }

        @Test
        fun `app filter is forwarded as-is`() {
            val filter = AppFilter(
                mode = ru.shapovalov.bedlam.core.appfilter.domain.model.AppFilterMode.Allowlist,
                packages = setOf("com.example.app", "com.example.other"),
            )
            val plan = planner().plan(RoutingConfig(), filter)
            assertEquals(filter, plan.appFilter)
        }
    }

    @Nested
    inner class PreApi33 {

        @Test
        fun `excludedV4 always empty - subtraction is baked into claimed list`() {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            assertTrue(plan.excludedV4.isEmpty())
            assertTrue(plan.excludedV6.isEmpty())
        }

        @Test
        fun `claimedV4 omits LAN ranges when bypass-LAN is on`() {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(bypassLan = true),
                AppFilter(),
            )
            val lanProbe = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
            val isLanCovered = plan.claimedV4.any {
                CidrMath.contains(it, Cidr.V4(lanProbe, 32))
            }
            assertFalse(isLanCovered)
            val publicProbe = byteArrayOf(8, 8, 8, 8)
            val isPublicCovered = plan.claimedV4.any {
                CidrMath.contains(it, Cidr.V4(publicProbe, 32))
            }
            assertTrue(isPublicCovered)
        }

        @Test
        fun `resolver host route survives TUN prefix subtraction and LAN bypass`() {
            for (bypassLan in listOf(true, false)) {
                val plan = planner(supportsExclude = false).plan(
                    RoutingConfig(bypassLan = bypassLan),
                    AppFilter(),
                )
                val resolver = Cidr.parse("$resolverV4/32")
                val iface = Cidr.parse("${tunV4.asString().substringBefore('/')}/32")
                assertTrue(plan.claimedV4.any { CidrMath.contains(it, resolver) }, "bypassLan=$bypassLan")
                assertFalse(plan.claimedV4.any { CidrMath.contains(it, iface) }, "bypassLan=$bypassLan")
                assertTrue(plan.claimedV6.any { CidrMath.contains(it, Cidr.parse("$resolverV6/128")) })
            }
        }

        @Test
        fun `preset routes survive subtraction of an overlapping direct route`() {
            for (preset in presets) {
                for (transport in DnsPresets.supportedTransports(preset.mode)) {
                    val plan = planner(supportsExclude = false).plan(
                        RoutingConfig(
                            dnsMode = preset.mode,
                            dnsTransport = transport,
                            sources = listOf(asnSource("provider", preset.directRoutes)),
                        ),
                        AppFilter(),
                    )
                    val label = "${preset.mode} $transport"
                    assertTrue(plan.excludedV4.isEmpty() && plan.excludedV6.isEmpty(), label)
                    val claimed = plan.claimedV4 + plan.claimedV6
                    for (hostRoute in preset.hostRoutesV4 + preset.hostRoutesV6) {
                        assertTrue(claimed.any { CidrMath.contains(it, hostRoute) }, "$label $hostRoute")
                    }
                    for (neighbour in preset.directNeighbours.map { Cidr.parse(it) }) {
                        assertFalse(claimed.any { CidrMath.contains(it, neighbour) }, "$label $neighbour")
                    }
                }
            }
        }

        @Test
        fun `the Cloudflare fallback survives subtraction of an overlapping direct route`() {
            val plan = planner(supportsExclude = false).plan(
                RoutingConfig(
                    dnsMode = DnsMode.Custom,
                    dnsTransport = DnsTransport.Tls,
                    customDns = listOf("not a host"),
                    sources = listOf(cidrSource("1.1.1.0/24"), cidrSource("2606:4700:4700::/48")),
                ),
                AppFilter(),
            )
            val claimed = plan.claimedV4 + plan.claimedV6
            for (hostRoute in cloudflareV4 + cloudflareV6) {
                assertTrue(claimed.any { CidrMath.contains(it, hostRoute) }, "$hostRoute")
            }
            assertFalse(claimed.any { CidrMath.contains(it, Cidr.parse("1.1.1.2/32")) })
            assertFalse(claimed.any { CidrMath.contains(it, Cidr.parse("2606:4700:4700::2/128")) })
        }
    }

    @Test
    fun `auto mtu is the ipv6 minimum in every ipv6 mode`() {
        // The TUN always carries an IPv6 address, so establish() rejects less.
        for (mode in Ipv6Mode.entries) {
            val plan = planner().plan(RoutingConfig(ipv6Mode = mode), AppFilter())
            assertEquals(RoutingConfig.MIN_TUN_MTU, plan.mtu, "$mode")
        }
    }

    @Test
    fun `an explicit mtu wins over auto`() {
        val plan = planner().plan(RoutingConfig(mtu = 1400), AppFilter())
        assertEquals(1400, plan.mtu)
    }

    @Test
    fun `a saved mtu below the ipv6 minimum is lifted back to it`() {
        val plan = planner().plan(RoutingConfig(mtu = 1220), AppFilter())
        assertEquals(RoutingConfig.MIN_TUN_MTU, plan.mtu)
    }

    @Test
    fun `an unusable dns over quic entry falls back over TLS`() {
        val plan = planner().plan(
            RoutingConfig(dnsMode = DnsMode.Custom, dnsTransport = DnsTransport.Doq, customDns = listOf("nope!")),
            AppFilter(),
        )
        assertEquals(DnsTransport.Tls, plan.dnsUpstream.transport)
        assertEquals(DnsPresets.cloudflare(DnsTransport.Tls), plan.dnsUpstream.servers)
    }
}
