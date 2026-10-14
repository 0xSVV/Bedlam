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

Routing is rule based. CIDR ranges and autonomous systems (expanded to announced prefixes from RIPEstat) create IP routes. An enabled domain rule matches the exact DNS name: its query uses Direct System DNS, and validated A/AAAA answers receive short-lived protected TCP/UDP routes. Domain answers do not become OS-wide route exclusions. An unmatched name, including names used with IP/ASN-only rules, uses Remote DNS. Apps excluded from the VPN use Android's DNS and remain outside Bedlam's DNS control. You can tunnel IPv6, block it, or leave it outside the VPN.

Remote DNS goes through the tunnel to Cloudflare, Google, your own servers, or your network's resolvers. Choose plain UDP or TCP, DNS over TLS, DNS over QUIC, DNS over HTTPS, or DNS over HTTP/3. IPv4 and IPv6 resolvers both work. Cloudflare and Google DoT/DoH presets use provider hostnames and have no generated numeric fallback; a failed hostname lookup is reported instead of silently changing resolver identity. Direct DNS controls for alternate providers and transports are disabled; Direct DNS currently uses the selected physical network's system resolvers over protected UDP for server bootstrap and enabled domain rules.

The 1.6.6 response cache is enabled by default. Turn it off in Routing settings to disable response reuse and storage while keeping active tunnel connections and routing state.

You can set the tunnel MTU from 1280 to 9000 bytes. Auto uses 1280. 1280 is the minimum link MTU of IPv6. The interface always holds an IPv6 address, so `VpnService.Builder.establish` refuses a smaller value.

GeoIP is deliberately out of scope. Bedlam bundles no `geoip.dat` or `.mmdb`, and matches no country or category. That kind of routing belongs to a general-purpose engine like sing-box, which has a rule-matching layer and bundled geo databases. Bedlam is a focused tunnel, and keeps its routing explicit by design.

## Building

You need Go, gomobile and gobind, the Android NDK, and the submodule checked out (`git submodule update --init --recursive`). Releases use the Go and NDK versions in `.github/toolchain.env`, and gomobile and gobind at the `golang.org/x/mobile` version that `hysteria/golib/go.mod` selects:

```sh
v=$(cd hysteria/golib && go list -m -f '{{.Version}}' golang.org/x/mobile)
go install golang.org/x/mobile/cmd/gomobile@$v golang.org/x/mobile/cmd/gobind@$v
```

Set `ANDROID_NDK_HOME` to the pinned NDK, or the build picks the highest NDK installed. After that, `./gradlew assembleDebug` builds everything, Go core included.

Bedlam vendors the Hysteria 2.13.0 core at a pinned commit and uses sing-tun 0.9.6. `./gradlew :hysteria:updateHysteriaCore` moves the core submodule to upstream's latest. Run it deliberately, review the diff, then rebuild. A regular build never touches the pin. CI accepts only a core commit that carries upstream release tags. If upstream's latest commit has none, check out the release tag in `hysteria/upstream` instead. Then set `HYSTERIA_CORE_COMMIT` and `HYSTERIA_CORE_TAGS` in `.github/toolchain.env` to that commit and its tags, for example `app/v2.13.0 core/v2.13.0 extras/v2.13.0`. CI rejects a checkout whose core differs from them.

## License

GPL-3.0-or-later. Bedlam links GPL-licensed components (sing-tun) and ships under the same terms.
