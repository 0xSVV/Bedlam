# Bedlam

Hysteria 2 Android client, built directly on the protocol core.

Bedlam runs the upstream [Hysteria 2](https://github.com/apernet/hysteria) core (the same Go implementation as the official client) and drives it from Kotlin. There is no bundled `hysteria` binary and no local SOCKS5 or HTTP proxy in front of it. Traffic enters a TUN interface, passes through an in-process userspace network stack, and leaves over QUIC. It tunnels the whole device, so individual apps have no proxy to point at.

It connects and routes traffic today. The app around it is still short of a first release.

## What it does

- The full Hysteria 2 configuration: authentication, TLS (custom SNI, custom CA, certificate pinning, mutual TLS), Salamander obfuscation, QUIC window and timeout tuning, BBR and Brutal congestion control, bandwidth limits, and port hopping with configurable intervals.
- A real TUN device backed by a gVisor userspace stack, carrying both TCP and UDP. DNS resolves through the tunnel and is cached, so other apps need no proxy settings.
- Import straight from `hysteria2://` and `hy2://` links.
- Per-app allow and block lists, and rule-based split tunnelling.
- Reconnects on its own through QUIC keep-alive, an idle watchdog, and a re-dial when the network switches between Wi-Fi and mobile.
- A Quick Settings tile, always-on VPN support, and a foreground service that holds up under aggressive battery management.

The UI is Jetpack Compose and Material 3 over a unidirectional architecture. It makes no analytics or tracking calls, and reaches the network only for the tunnel itself and the route sources you add.

## Routing

Routing is rule based. You decide what bypasses the tunnel and what goes through it using three kinds of source: CIDR ranges, autonomous systems (an ASN expands to its announced prefixes, pulled live from RIPEstat), and domains (resolved to addresses). The engine coalesces and subtracts those into a minimal route set, with LAN bypass and a configurable IPv6 mode.

It does not do GeoIP. There is no bundled `geoip.dat` or `.mmdb`, and no matching by country or category. Those files are large binary blobs that fall out of date the day they ship, so Bedlam resolves routes from sources that stay current instead of shipping a frozen database. The catch is that you cannot route a whole country with one rule the way a GeoIP client can; you describe what you want with CIDRs, ASNs, and domains. If that does not fit how you route, a GeoIP-based client is the better choice.

## License

GPL-3.0-or-later. Bedlam links GPL-licensed components (sing-tun) and ships under the same terms.
