# Bedlam

Hysteria 2 Android client, built directly on the protocol core.

Bedlam runs the upstream [Hysteria 2](https://github.com/apernet/hysteria) core (the same Go implementation as the official client) and drives it from Kotlin. There is no bundled `hysteria` binary and no local SOCKS5 or HTTP proxy in front of it. Traffic enters a TUN interface, passes through an in-process userspace network stack, and leaves over QUIC. It tunnels the whole device, so individual apps have no proxy to point at.

Bedlam publishes signed per-ABI APKs (arm64-v8a, armeabi-v7a, x86_64) on GitHub.

## What it does

- The full Hysteria 2 configuration: authentication, TLS (custom SNI, custom CA, certificate pinning, mutual TLS), Salamander and Gecko obfuscation, QUIC window and timeout tuning, BBR and Brutal congestion control, bandwidth limits, and port hopping with configurable intervals.
- Realm rendezvous mode: give a `realm://` address to reach a peer behind NAT through STUN and UDP hole punching. You do not need a public IP or port forwarding.
- A real TUN device on a gVisor userspace stack that carries both TCP and UDP. Bedlam resolves DNS through the tunnel and caches it, so other apps need no proxy settings.
- Import from `hysteria2://` and `hy2://` links or a profile JSON, from the clipboard or straight from a link tapped in another app.
- Per-app allow and block lists, and rule-based split tunnelling.
- Reconnects on its own: QUIC keep-alive, a watchdog that probes a stalled tunnel and re-resolves the server address, and an immediate re-dial when the network switches between Wi-Fi and mobile.
- A Quick Settings tile, always-on VPN support, and a foreground service that holds up under aggressive battery management.
- In-app updates from GitHub releases. Bedlam offers a new version on launch with its release notes, shows download progress, and installs the APK that matches the device ABI.

The UI is Jetpack Compose and Material 3 over a unidirectional architecture. It makes no analytics or tracking calls, and reaches the network only for the tunnel itself, the route sources you add, and the release check against GitHub.

## Routing

Routing is rule based. Three kinds of source decide what bypasses the tunnel and what goes through it: CIDR ranges, autonomous systems (Bedlam expands an ASN to its announced prefixes, live from RIPEstat), and domains (Bedlam resolves them to addresses). The engine coalesces and subtracts those into a minimal route set, with LAN bypass. You can tunnel IPv6, block it, or leave it outside the VPN.

DNS goes through the tunnel to Cloudflare, Google, your own servers, or your network's resolvers. Choose plain UDP or TCP, DNS over TLS, DNS over QUIC, DNS over HTTPS, or DNS over HTTP/3. IPv4 and IPv6 resolvers both work.

The tunnel MTU is settable, and Auto picks it from the IPv6 mode: 1220 bytes without IPv6, 1280 with it. Hysteria carries a relayed UDP packet in one QUIC packet of at most 1200 bytes, so a wider interface splits every full packet in two unreliable halves.

GeoIP is deliberately out of scope. Bedlam bundles no `geoip.dat` or `.mmdb`, and matches no country or category. That kind of routing belongs to a general-purpose engine like sing-box, which has a rule-matching layer and bundled geo databases. Bedlam is a focused tunnel, and keeps its routing explicit by design.

## Building

You need Go 1.25+, gomobile and gobind (`go install golang.org/x/mobile/cmd/{gomobile,gobind}@latest`), the Android NDK, and the submodule checked out (`git submodule update --init --recursive`). After that, `./gradlew assembleDebug` builds everything, Go core included.

Bedlam vendors the Hysteria core at a pinned commit. `./gradlew :hysteria:updateHysteriaCore` moves it to upstream's latest. Run it deliberately, review the diff, then rebuild. A regular build never touches the pin.

## License

GPL-3.0-or-later. Bedlam links GPL-licensed components (sing-tun) and ships under the same terms.
