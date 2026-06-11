# Bedlam

Hysteria 2 Android client, built directly on the protocol core.

Bedlam runs the upstream [Hysteria 2](https://github.com/apernet/hysteria) core (the same Go implementation as the official client) and drives it from Kotlin. There is no bundled `hysteria` binary and no local SOCKS5 or HTTP proxy in front of it. Traffic enters a TUN interface, passes through an in-process userspace network stack, and leaves over QUIC. It tunnels the whole device, so individual apps have no proxy to point at.

Releases are signed per-ABI APKs (arm64-v8a, armeabi-v7a, x86_64) published on GitHub.

## What it does

- The full Hysteria 2 configuration: authentication, TLS (custom SNI, custom CA, certificate pinning, mutual TLS), Salamander obfuscation, QUIC window and timeout tuning, BBR and Brutal congestion control, bandwidth limits, and port hopping with configurable intervals.
- A real TUN device backed by a gVisor userspace stack, carrying both TCP and UDP. DNS resolves through the tunnel and is cached, so other apps need no proxy settings.
- Import straight from `hysteria2://` and `hy2://` links.
- Per-app allow and block lists, and rule-based split tunnelling.
- Reconnects on its own: QUIC keep-alive, a watchdog that probes a stalled tunnel and re-resolves the server address, and an immediate re-dial when the network switches between Wi-Fi and mobile.
- A Quick Settings tile, always-on VPN support, and a foreground service that holds up under aggressive battery management.

The UI is Jetpack Compose and Material 3 over a unidirectional architecture. It makes no analytics or tracking calls, and reaches the network only for the tunnel itself and the route sources you add.

## Routing

Routing is rule based. You decide what bypasses the tunnel and what goes through it using three kinds of source: CIDR ranges, autonomous systems (an ASN expands to its announced prefixes, pulled live from RIPEstat), and domains (resolved to addresses). The engine coalesces and subtracts those into a minimal route set, with LAN bypass. You can tunnel IPv6, block it, or leave it outside the VPN. DNS resolves through the tunnel against Cloudflare, Google, your own servers, or your network's resolvers.

GeoIP is deliberately out of scope: no bundled `geoip.dat` or `.mmdb`, and no country- or category-level matching. That kind of routing belongs to a general-purpose routing engine like sing-box, with a dedicated rule-matching layer and bundled geo databases. Bedlam is a focused tunnel rather than a routing engine, and keeps its routing explicit and transparent by design. If you need geographic rule sets, that is what a full routing engine is for.

## Building

You need Go 1.25+, gomobile and gobind (`go install golang.org/x/mobile/cmd/{gomobile,gobind}@latest`), the Android NDK, and the submodule checked out (`git submodule update --init --recursive`). After that, `./gradlew assembleDebug` builds everything, Go core included.

The Hysteria core is vendored at a pinned commit. `./gradlew :hysteria:updateHysteriaCore` moves it to upstream's latest; run it deliberately, review the diff, and rebuild. A regular build never touches the pin.

## License

GPL-3.0-or-later. Bedlam links GPL-licensed components (sing-tun) and ships under the same terms.
