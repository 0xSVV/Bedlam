# 005 — Resolve bootstrap and explicit bypass domains through Direct DNS

Planned at `a35e3890ae00c5d9925f3fb9e40e3761ca149f7d`, 2026-10-14. Priority P1; effort L (multi-day); risk HIGH; category feature/architecture. Status: PARTIAL; not release-ready. Depends on 001–004. This is the largest package; do not estimate it as another DNS selector field.

## Contract approved by the user

Direct DNS handles bootstrap and enabled, explicitly configured bypass domains. Remote DNS handles other captured name queries, including names whose eventual addresses happen to match CIDR/ASN-only bypass rules. Excluded applications remain outside Bedlam and use Android/network DNS. The user chose this bounded scope instead of a general routing redesign.

No automatic failure crossover: failed Direct DNS does not query Remote; failed Remote DNS does not query Direct. Similarly, a flow selected for protected direct forwarding never retries through Hysteria. App-owned DoH/DoT is ordinary traffic, not DNS intercepted on port 53. Matching an exact domain must not silently turn into a suffix/subdomain rule.

## Current architecture and why a selector alone is insufficient

- `hysteria/src/main/kotlin/ru/shapovalov/hysteria/HysteriaClientImpl.kt:80,93` calls `NewSession` before attaching TUN. Native `session.go:68` calls `resolveHost` on connection/reconnection.
- `hysteria/golib/config.go:270–283` uses `net.DefaultResolver`; its hopping branch uses `udphop.ResolveUDPHopAddr`, whose upstream implementation calls `net.ResolveIPAddr`.
- `realm.go:73` omits the supported STUN resolver injection. `realmHTTPClient` at 168 protects TCP sockets but its dialer still performs default DNS lookup. ECH is supplied configuration parsed in `ech.go`; there is no existing ECH DNS-discovery feature to replace.
- `dns_upstream.go:68,100` constructs all resolvers with a Hysteria `client.Client`. `dns_https.go:124` and `dns_tls.go` dial via tunnel helpers.
- `app/.../core/routing/data/SystemDnsProvider.kt:21–55` deliberately filters private resolvers for Remote use. Direct System must include usable private resolvers from the selected underlying network.
- `app/.../core/routing/data/resolver/DnsDomainResolver.kt:16` resolves domain source snapshots using `InetAddress.getAllByName`. `RoutePlanner.kt:43–65` turns those snapshots into OS exclusions. New DNS answers can differ from the stored snapshots, so changing only the resolver would return addresses that still go through the tunnel.
- `tun.go:218,285` intercepts port 53. Non-DNS TCP goes to `h.client.TCP` at 228; UDP goes to `handleUDPRelay` at 289. These are the narrow insertion points for domain-derived direct traffic.

## Chosen design

### Session policy and protected DNS transport

Use the runtime DNS policy from 003 at native session creation. Add a small transport dependency exposing the TCP dial and UDP packet/session operations the existing resolvers actually need. Implement a tunneled adapter and a protected direct adapter; preserve existing DNS framing, TLS validation, pools, HTTP/QUIC behavior, resolver order and bounded scheduling. Do not impersonate an entire Hysteria client if that introduces unrelated lifecycle methods.

Use the underlying-network snapshot from 004. Every direct TCP/UDP socket must be protected from the VPN and bound to that selected network before traffic. Failure to protect/bind is fatal for that operation; never continue with an ordinary default socket. A lost network cancels old direct work, closes pools and flows, and retires the Direct response-cache namespace and domain leases. Old callbacks must not populate the new generation. A cache-only toggle is different: it changes only the response-cache epoch through 003's live API and preserves pools, network/routing generations, unexpired leases and active flows. Avoid process-wide `bindProcessToNetwork` or global mutation of `net.DefaultResolver`.

Expose a narrow Direct lookup service through the hysteria module for app-owned domain-source refresh, with lifecycle independent of an established Hysteria tunnel. It must use the same policy, validation and network snapshot as native bootstrap. Kotlin owns Android network selection/protection, Go owns DNS wire exchange. Keep native bindings behind `HysteriaClient`/a dedicated interface, never import generated Golib types into app UI. The off-tunnel service must also work when the VPN is stopped; bind to a selected physical network and apply protection whenever an existing VPN service owns the socket. Test absence/loss of that service rather than treating a missing callback as implicit permission to use the active VPN.

Support the existing selectable transports through the adapters: UDP, TCP, DoT, DoQ, DoH and HTTP/3 in Custom; provider choices keep their current supported transport set (no invented Cloudflare/Google DoQ preset). If a transport cannot support correct protection/identity, disable that Direct option explicitly and report the unsupported case; do not silently substitute another transport.

### Bootstrap matrix

| Lookup | Required path |
|---|---|
| Ordinary Hysteria server, start/reconnect | Direct A/AAAA lookup; numeric literal requires no lookup; preserve server-name TLS identity |
| Port-hopping hostname | Direct resolve first, retain original port union, construct upstream hopping address without its implicit DNS lookup |
| Realm STUN names | Inject Direct resolver through upstream `realm.STUNConfig.Resolver` |
| Realm rendezvous HTTP hostname | Direct lookup in protected dialer; preserve HTTPS authority/SNI and existing TLS options |
| Direct DoT/DoH/DoQ/HTTP3 endpoint hostname | Bootstrap only this resolver endpoint through the selected underlying network's resolver; dial resulting addresses while retaining hostname identity |
| Remote encrypted resolver hostname | Remains server-side through Hysteria; do not expose it to local Direct lookup |
| ECH | Keep supplied config parsing; no new DNS discovery feature |

The endpoint-bootstrap step is explicit and bounded. It may use the network/ISP resolver to find the user's chosen encrypted Direct resolver; it does not mean ordinary queries silently fall back to ISP DNS. Numeric socket destinations derived from a hostname are not numeric fallback resolver endpoints. Keep provider presets hostname-only as requested. A self-referential custom bootstrap setup must produce a clear configuration error instead of recursion. Tag bootstrap lookups separately from application DNS: payload IPv6 Disabled must not suppress AAAA needed to reach an IPv6-only outer server, STUN service or Direct resolver.

### Query selection and IPv6

Normalize stored rule names and incoming QNAME using one documented canonical form: lowercase ASCII/IDNA, no final root dot, valid DNS labels. Preserve original query bytes/transaction IDs for the wire exchange. Select by enabled exact rule name before opening an upstream connection; keep rule ID for ownership. All questions matching that domain use Direct, including non-address records; only validated address-bearing answers grant forwarding leases.

Disabled IPv6 answers AAAA locally with NODATA and blocks captured IPv6 payload while permitting the protected outer connection to an IPv6 server. BypassOnly intentionally bypasses all IPv6: send captured AAAA questions to Direct even without an exact domain match, and keep IPv6 routes outside TUN. Enabled uses the ordinary selector for A/AAAA. TCP and UDP DNS follow identical policy. Unknown/unmatched names never get a speculative Direct query for CIDR/ASN classification.

### Keep domain answers consistent with forwarding

Remove **Domain-derived** IP exclusions from Android's route list. Keep enabled exact-domain rules independent of whether old stored CIDRs exist or fit the OS route budget. CIDR, ASN and LAN exclusions stay in the OS planner. Old domain resolution rows may remain for UI diagnostics, but are no longer routing authority.

Add a bounded native address registry, initially 4096 address/owner leases per session, keyed by normalized IP plus rule ID and policy/network generations. Admit only validated IN A/AAAA records reachable from the requested name through the answer CNAME chain; unrelated answer/additional-section addresses do not grant bypass. Validate question, response type and bounds, not only transaction ID. Use the minimum relevant address/CNAME remaining TTL. Registry insertion must complete **before** sending the DNS response, including response-cache hits. A cache hit must not extend the original lease merely by replaying a response.

For TTL zero and very short TTLs, use a separate **30-second minimum routing lease** so an application can open a connection after receiving an uncached answer. This is proposed implementation policy, not DNS response caching: do not raise the returned DNS TTL or reuse the answer. Cache off still creates these routing leases from each fresh answer. Bound storage; reject an answer with SERVFAIL if new permissions cannot be admitted, rather than evicting live unexpired leases and silently changing routes. Shared IP owners are reference-counted; removing one rule does not remove another's valid lease.

After local DNS interception and IPv6 enforcement, consult the registry before normal Hysteria forwarding. A matching TCP connection or UDP mapping uses the protected direct dialer, pins that choice for its lifetime, and has bounded idle/resource limits. Count these bytes separately from tunneled traffic; they must not masquerade as Hysteria throughput or make the tunnel watchdog believe it received tunnel payload. Lease expiry affects new flows, not existing ones; rule removal and network change close affected flows and clear obsolete permissions. New IP-only connections after lease expiry follow ordinary IP routing unless a fresh DNS answer renews the lease.

This retains an IP-wide limitation: unrelated hostnames sharing a leased CDN IP can also go Direct. There is no UID/domain identity in a bare destination IP. Do not claim hostname-perfect routing, add SNI inspection, or introduce fake-IP. For this release the address-routing guarantee covers learned A/AAAA responses. HTTPS/SVCB hints, cached answers obtained before the VPN starts, addresses reused from an application's cache after a network/policy generation change, and app-private encrypted DNS may bypass this learning path; document this boundary and include smoke cases. Bedlam cannot forcibly flush those application caches. If the product requires all such traffic to follow a hostname rule, stop and propose a separately scoped router feature instead of silently enlarging this package.

## Allowed changes

Native `session.go`, `config.go`, `realm.go`, `protect.go`, `dns*.go`, `tun.go` plus narrowly named new transport/policy/domain-registry/direct-relay files and focused tests. Kotlin hysteria API/config/wrapper and narrow Direct lookup API/tests. App routing model/planner/domain resolver/refresh/DI, network snapshot service and routing UI/help/strings/tests. Preserve public profile import/export. New dependencies need a concrete wire-parser requirement and review; prefer existing Go `x/net` DNS parsing support where adequate. No upstream source patches, GeoIP, broad app router replacement, global platform DNS changes, unrelated endpoints or automatic direct fallback for tunneled names.

## Execution and gates

## Implementation checkpoint (2026-10-14)

Implemented a bounded System UDP vertical slice. Native bootstrap for Hysteria hostnames, port hopping, realm STUN and rendezvous HTTP uses the selected network's numeric system DNS servers and a bind-then-protect socket callback. `StartTUN` refreshes the session-level resolver snapshot, and Android reapplies TUN settings on each settled network/DNS change. Exact enabled domain questions select Direct for UDP and TCP DNS; other captured questions remain Remote. A/AAAA response validation follows the queried name through answer CNAMEs, computes the minimum relevant TTL, admits bounded address/rule/generation leases before replying, and does not extend an existing lease on cache replay. Learned destinations use protected direct TCP and connected UDP relays. Domain-derived CIDR snapshots are neither OS exclusions nor asynchronously resolved by app route refresh; only the exact stored hostname is sent to native policy. Direct traffic is excluded from Hysteria throughput counters.

Native focused tests cover UDP/TCP selection and admission-before-response. The Go suite passes on Darwin arm64 and the Android unit/build tasks pass. The Direct provider/transport/custom controls remain disabled; the UI explains that System DNS over protected UDP is active. This package is still **partial** because Direct DoT/DoH/DoQ/HTTP3 and custom server selection are not implemented, and the Linux TUN, race, device-network, migration and full direct-routing matrix have not run. Do not mark 005 release-complete until those risks are resolved or explicitly accepted in release review.

1. Check `git diff --stat a35e389..HEAD -- hysteria app` against completed 001–004. Write pure selector and adapter contract tests first. Run `(cd hysteria/golib && go test -tags with_gvisor ./...)` and `./gradlew testDebugUnitTest`; baseline must pass.
2. Extract minimal dial operations and keep the tunneled adapter behavior identical. Re-run all DNS transport tests, including HTTP1 fallback, multiplexing, failover, TLS SNI and DoQ/HTTP3. No Direct behavior yet; expected existing tests unchanged and green.
3. Add protected Direct adapters and pre-session lookup service. Test every bootstrap row using fake DNS/network callbacks with no real internet dependency. Protected socket checks must precede first send/connect. Run the Go suite plus Kotlin API/serialization tests; check generated bindings build with `./gradlew :hysteria:buildGolib`.
4. Connect the exact-domain selector to TCP/UDP DNS; introduce registry and flow-forwarding unit tests before switching planner authority away from Domain snapshots. Test address change A→B from a CDN, no old stored CIDRs, rule removal, disabled rule, shared owners, full registry and unrelated injected records. Run native full/race suites.
5. Switch Domain source refresh to Direct; stop using its stored CIDRs as OS exclusions. Wire UI selections and live updates end to end using 004's coordinator. Changing resolver/network must retire the correct caches, pools, registry and flows together. Run `./gradlew testDebugUnitTest` and real Room tests from 003/004.
6. Perform packet-level Android verification on API29/30 and API33+: capture test DNS and application traffic at both client network and Hysteria server. Expected Direct test names only at Direct resolver, Remote test names only through server, excluded apps unchanged, and new domain-answer destinations direct before the app connects. Test bootstrap while tunnel down, while old TUN remains during reconnect, IPv6 server address with payload IPv6 Disabled, and Wi-Fi/mobile/same-network DNS changes. Record redacted evidence under the eventual release verification record.

## Mandatory fixtures

- Same QNAME through Direct and Remote does not share response data; cache off issues fresh sequential/concurrent queries while keeping transport pools.
- Resolve a Direct domain, establish TCP/UDP flows, toggle cache off and on without another lookup: active flows and unexpired lease remain Direct; no TUN factory call or pool replacement occurs.
- Endpoint bootstrap cannot recursively invoke itself; preserved DoH authority and DoT certificate hostname even when dialing IPs.
- Direct unreachable, Remote unreachable, protection failure and no selected network each fail without crossover.
- Registry admission precedes DNS delivery; compressed CNAME chain; mismatched/malformed response; unrelated additional data; TTL-zero immediate TCP/UDP connection; cache-hit aging; saturation; multiple owners; active flow after lease expiry; rule deletion/network change closes affected flows.
- System Direct includes private resolvers from the chosen network; Remote System retains its old semantics. Packet routing does not loop into TUN.
- Exact `example.com` does not match `notexample.com` or `www.example.com`; case/root dot/IDNA equivalent names do match.
- CIDR/ASN-only unclassified name uses Remote; resulting connection still follows the existing IP rule. Domain source ignored by OS CIDR budget still participates in native exact matching.

## Done / stop / maintenance

All automated suites and the two Android routing-path smoke tests pass; leak/crossover assertions are tested, UI limitations match real behavior, and only scoped files changed. Update the index. Stop if network binding cannot be guaranteed before send, domain answers cannot be registered before delivery, or accepted scope requires routing excluded apps. Keep route leases and response caching visibly separate in code, telemetry and tests. This package is not complete with a settings-only implementation.
