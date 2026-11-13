# Bedlam

Hysteria 2 Android client, built directly on the protocol core.

Bedlam runs the upstream [Hysteria 2](https://github.com/apernet/hysteria) core (the same Go implementation as the official client) and drives it from Kotlin. There is no bundled `hysteria` binary and no local SOCKS5 or HTTP proxy in front of it. Traffic enters a TUN interface, passes through an in-process userspace network stack, and leaves over QUIC. It tunnels the whole device, so individual apps have no proxy to point at.

Bedlam publishes signed per-ABI APKs (arm64-v8a, armeabi-v7a, x86_64) on GitHub.

## What it does

- The full Hysteria 2 configuration: authentication, TLS (custom SNI, custom CA, certificate pinning, mutual TLS), Salamander and Gecko obfuscation, QUIC window and timeout tuning, BBR and Brutal congestion control, bandwidth limits, and port hopping with configurable intervals.
- Realm rendezvous mode: give a `realm://` address to reach a peer behind NAT through STUN and UDP hole punching. You do not need a public IP or port forwarding.
- A real TUN device on a gVisor userspace stack that carries both TCP and UDP. Captured apps use the selected Remote DNS provider; response caching is on by default and can be disabled in Routing settings.
- Import from `hysteria2://` and `hy2://` links or a profile JSON, from the clipboard or straight from a link tapped in another app.
- Per-app allow and block lists, and rule-based split tunnelling.
- Reconnects on its own: QUIC keep-alive, a watchdog that probes a stalled tunnel and re-resolves the server address, and an immediate re-dial when the network switches between Wi-Fi and mobile.
- A Quick Settings tile, always-on VPN support, and a foreground service that holds up under aggressive battery management.
- In-app updates from GitHub releases. Bedlam offers a new version on launch with its release notes, shows download progress, and installs the APK that matches the device ABI.

The UI is Jetpack Compose and Material 3 over a unidirectional architecture. It makes no analytics or tracking calls, and reaches the network only for the tunnel itself, the route sources you add, and the release check against GitHub.

## Routing

Routing is rule based. CIDR ranges and autonomous systems (expanded to announced prefixes from RIPEstat) create IP routes. An enabled domain rule matches the exact DNS name: its query uses Direct DNS, and validated A/AAAA answers receive short-lived protected TCP/UDP routes. Domain answers do not become OS-wide route exclusions. An unmatched name, including names used with IP/ASN-only rules, uses Remote DNS. Apps excluded from the VPN use Android's DNS and remain outside Bedlam's DNS control. You can tunnel IPv6, block it, or leave it outside the VPN.

Remote DNS goes through the tunnel to Cloudflare, Google, OpenDNS, AdGuard, custom servers, or your network's resolvers. Available transports depend on the provider: UDP, TCP, DNS over TLS, DNS over HTTPS, and the existing custom DNS over QUIC and provider-supported HTTP/3 options. AdGuard's preset uses its ad, tracker and phishing filtering service; this is DNS filtering, not a full content blocker.

Direct DNS goes outside the tunnel on the selected physical network. It resolves the Hysteria server before connecting and answers enabled direct-domain rules. Choose System, Cloudflare, Google, OpenDNS, AdGuard or Custom with UDP, TCP, TLS or HTTPS as supported by that provider. Encrypted presets include bootstrap IP addresses so they work without resolving their own hostname first. Custom encrypted endpoints require explicit bootstrap IPs; certificate and hostname verification remain enabled, and failures do not silently fall back to plaintext or system DNS. OpenDNS offers UDP, TCP and HTTPS.

In IPv6 **Bypass only** mode, IPv6 address queries also use Direct DNS. With System, UDP or TCP, the local network can see those names. Choosing Direct DNS over TLS or HTTPS encrypts them between the device and that provider; the provider still sees the queries. Apps excluded from the VPN use their own Android DNS path.

The 1.6.6 response cache is enabled by default. Turn it off in Routing settings to disable response reuse and storage while keeping active tunnel connections and routing state.

You can set the tunnel MTU from 1280 to 9000 bytes. Auto uses 1500. 1280 is the minimum link MTU of IPv6. The interface always holds an IPv6 address, so `VpnService.Builder.establish` refuses a smaller value.

GeoIP is deliberately out of scope. Bedlam bundles no `geoip.dat` or `.mmdb`, and matches no country or category. That kind of routing belongs to a general-purpose engine like sing-box, which has a rule-matching layer and bundled geo databases. Bedlam is a focused tunnel, and keeps its routing explicit by design.

## Building

You need Go, gomobile and gobind, the Android NDK, and the submodule checked out (`git submodule update --init --recursive`). Releases use the Go and NDK versions in `.github/toolchain.env`, and gomobile and gobind at the `golang.org/x/mobile` version that `hysteria/golib/go.mod` selects:

```sh
v=$(cd hysteria/golib && go list -m -f '{{.Version}}' golang.org/x/mobile)
go install golang.org/x/mobile/cmd/gomobile@$v golang.org/x/mobile/cmd/gobind@$v
```

Set `ANDROID_NDK_HOME` to the pinned NDK, or the build picks the highest NDK installed. After that, `./gradlew assembleDebug` builds everything, Go core included.

Bedlam vendors the Hysteria 2.13.0 core at a pinned commit and uses sing-tun 0.9.6. `./gradlew :hysteria:updateHysteriaCore` moves the core submodule to upstream's latest. Run it deliberately, review the diff, then rebuild. A regular build never touches the pin. CI accepts only a core commit that carries upstream release tags. If upstream's latest commit has none, check out the release tag in `hysteria/upstream` instead. Then set `HYSTERIA_CORE_COMMIT` and `HYSTERIA_CORE_TAGS` in `.github/toolchain.env` to that commit and its tags, for example `app/v2.13.0 core/v2.13.0 extras/v2.13.0`. CI rejects a checkout whose core differs from them.

## Testing DNS changes

Run the Go tests from `hysteria/golib` on Linux (WSL works): `go test -tags with_gvisor -ldflags=-checklinkname=0 ./...`. The suite starts local DNS, TLS, HTTPS and Hysteria servers and checks bootstrap, socket protection, resolver shutdown and cancellation. Public-provider probes are separate and opt-in: `BEDLAM_PUBLIC_DNS_TEST=1 go test -run TestPublicDNSPresets -v`. They need access to the relevant DNS ports; a network blocking port 853 cannot verify public DoT.

For emulator integration tests, first run `go run ./cmd/dnsfixture` from `hysteria/golib` on the host. Keep it running while executing `./gradlew :app:connectedDebugAndroidTest` on an Android emulator. The fixture listens on loopback and the test reaches it at `10.0.2.2`; it uses disposable credentials and a self-signed tunnel certificate. The VPN test checks a real client–server connection, Remote versus Direct DNS, IPv6 bypass queries and a live transport change. Other tests exercise the settings migration and Android UI.

## License

GPL-3.0-or-later. Bedlam links GPL-licensed components (sing-tun) and ships under the same terms.
