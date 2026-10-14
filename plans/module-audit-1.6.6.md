# App and hysteria module review for 1.6.6

Reviewed 2026-10-14 at `a35e3890ae00c5d9925f3fb9e40e3761ca149f7d`. Findings below were read in the current source by the coordinator after independent module reviews. They are source-backed issues, not claims that each failure was reproduced on a device. Effort: S = hours, M = roughly a day, L = multiple days including tests. Fix risk is distinct from impact. No source fixes were made during planning.

## Recommended release additions

All paths below are repository-relative. Priority order favors correctness on the paths the DNS work will change.

| ID | Finding / concrete trigger | Impact | Effort / risk / confidence | Evidence | Planned disposition |
|---|---|---|---|---|---|
| H01 | Kotlin closes an fd after native startup already closed it | Startup failure can double-close a descriptor; reuse can affect an unrelated socket | M / MED / HIGH | `hysteria/src/main/kotlin/ru/shapovalov/hysteria/HysteriaClientImpl.kt:206`; `hysteria/golib/tun.go:68` | 001: explicit borrowed/owned fd contract, fault-injection coverage |
| H02 | An operation on old client A reports an error after healthy B is installed | `markDead` closes B and creates avoidable reconnects | M / MED / HIGH | `hysteria/golib/reconnect.go:207`, `:295`, `:368`, `:457` | 002: conditional failure by connection identity |
| H03 | Uncached DNS flood meets a slow resolver across several UDP flows | Goroutines/queued copies grow without a session bound; queued time is outside timeout | M / MED / HIGH | `hysteria/golib/tun.go:298`, `:339`, `:350`; `dns_cache.go:73` | 002: session admission and total deadline |
| H04 | Cache cleared/off while ordinary or late exchange is running | Old work repopulates cleared cache or is joined by new requests | M / MED / HIGH | `hysteria/golib/dns_cache.go:66`, `:75`, `:83`, `:124`, `:210` | 003: generation-aware work, keys and stores |
| H05 | Upstream TTL 1–4 seconds or differing ECS/Cookie options | Answers outlive advertised TTL or cross request-specific cache/coalescing boundaries | M / MED (TTL LOW) / HIGH | `hysteria/golib/dns_cache.go:254`, `:307`, `:355`, `:458` | 003: preserve TTL, bypass unsafe EDNS caching/coalescing |
| A01 | Android 10/11 has Wi-Fi and mobile; any matching network is lost | Listener selects by callback order and may clear a still-valid selection | M / MED / HIGH | `app/src/main/kotlin/ru/shapovalov/bedlam/core/vpn/DefaultNetworkListener.kt:32`, `:37`, `:60` | 004: per-network state and consistent snapshot |
| A02 | Settings and network changes reapply simultaneously | Builder routes/app filter can differ from the native DNS/IPv6 policy | M / MED / HIGH | `app/src/main/kotlin/ru/shapovalov/bedlam/core/vpn/BedlamVpnService.kt:362`, `:375`, `:498`; `hysteria/.../HysteriaClientImpl.kt:172` | 004: immutable plan plus shared coordinator |
| A03 | IPv6 Disabled with LAN or source exclusions; BypassOnly AAAA lookup | Disabled can leave IPv6 bypass holes; BypassOnly suppresses usable AAAA | M / MED / HIGH | `app/.../core/routing/engine/RoutePlanner.kt:25`, `:64`, `:98`, `:110`; `hysteria/golib/tun.go:131` | 004/005: explicit IPv6 policy, effective-route tests |
| A04 | Delete a domain/ASN while asynchronous resolution finishes | FK insertion fails and can escape UI coroutine; old result can overwrite newer state | S–M / LOW / HIGH | `app/.../core/routing/domain/usecase/RefreshRouteSourcesUseCase.kt:29`; `data/local/RoutingDao.kt:59`; `ResolvedCidrEntity.kt:10`; `AddRouteSourceUseCase.kt:16` | 004: atomic conditional result commit |
| A05 | Scheduled DNS/RIPE refresh returns a transient Result failure | Worker reports success; stale routes can remain until next daily/manual refresh | S / LOW / HIGH | `app/.../core/routing/work/RouteRefreshWorker.kt:19`; `domain/usecase/RefreshRouteSourcesUseCase.kt:33` | 004: structured outcome and bounded retry |

The ellipsis in table paths means the existing `src/main/kotlin/ru/shapovalov/bedlam` or `src/main/kotlin/ru/shapovalov/hysteria` package root; exact execution scope is expanded in each package plan.

## Smaller improvements included where those paths change

- **One transactional route snapshot.** `RoutingRepositoryImpl.kt:29–44` combines separate observable table streams and reads one resolved list per source during `get`. While fixing A04/A02, obtain a consistent config/source/result snapshot and avoid the 1+N reads. Do not rewrite unrelated repositories.
- **One export snapshot and complete redaction.** `feature/logs/data/LogExporter.kt:30,36` reads routing once for the header and again for redaction. A concurrent custom resolver edit can make those differ. Package 006 uses one snapshot and includes new Direct addresses in redaction tests.
- **Extract only needed seams.** The new policy selector, network selector, DNS scheduler, domain lease registry and apply coordinator should be independently testable. Avoid adding all this logic to `BedlamVpnService.kt` or `tun.go` as another large conditional tree. Their file size alone was not treated as a bug.

## Investigation / future options, not required implementation scope

### H06 — Do not equate a single DNS endpoint's reachability with tunnel health

`hysteria/golib/reconnect.go:21` uses hardcoded `1.1.1.1:53`; `checkNow:376–383` and `probe:457–461` can tear down a tunnel on DNS timeouts. `probeIndicatesDead:477–485` exempts destination DialError but not a timeout. A test server can carry DoH/ordinary traffic while blackholing outbound TCP/53, making this a possible false liveness failure. It was already acknowledged as an investigation in `docs/release-plan-1.5.4.md:220–222`, not a newly introduced regression.

Impact high; effort M–L; fix risk HIGH; confidence HIGH in code behavior, MED in prevalence. First reproduce the healthy-tunnel/blocked-probe case and inspect 2.13.0 connection-health signals. Prefer evidence independent of one external resolver; preserve the dead-tunnel recovery budget. Do not just remove the watchdog or use Direct DNS as proof the tunnel works. Recommend a separate follow-up if the release cannot support this investigation. It is not promised fixed in 1.6.6.

### Product directions after 1.6.6

1. **A small DNS diagnostic action:** show selected path, resolver reachability and cache state using synthetic test names, with redacted export. The current logs and repeated “why are there warnings?” chat questions support this. Trade-off: active probes must not trigger a lazy tunnel unexpectedly or replace transport health detection. No need for a permanent per-domain log/history.
2. **A stronger domain-routing model only if demanded:** exact-domain address leases retain an IP-wide shared-CDN limitation. SVCB hints, app-private DNS, excluded apps, domain suffix rules and GeoIP are distinct expansions. A general routing engine/fake-IP system would need a separate product decision; adding it implicitly would make this release hard to validate.

## Considered and rejected or deferred

- Reset all Android settings to upstream defaults: conflicts with deliberate Android behavior; no new evidence justifies reverting it.
- MTU Auto=1500: not selected by the current request; the historical logs already show 1500, so it does not explain those errors. Keep Auto=1280 and user-selected values.
- Redo Lazy mode: already shipped in 1.6.2; test it with new bootstrap handling instead.
- “All DNS errors fixed” or HTTP 413 root cause proven: no post-1.6.5 log/reproducer supports those claims. Use fault-injection regression cases and bounded diagnostics.
- Remove every numeric address: remove only generated DoT/DoH fallback endpoints. Custom addresses, socket destinations and protective host routes have separate roles.
- Cache removal: user selected default-on with off switch. Keep low-speed pooling and useful cache-on behavior.
- Direct DNS for all excluded apps or CIDR/ASN name classification: user chose bounded coverage; Android exclusion means those apps' traffic never reaches Bedlam. No speculative direct lookup for otherwise Remote names.
- Treat upstream sing-tun upgrade as automatic: Bedlam directly links it; package 001 performs a separate dependency migration.
- Cosmetic redesign, icon changes, new VayDNS client, GeoIP and generic plugin systems: outside this release.
- Broad APK updater rewrite: signer checks and bounded downloads already exist; review found no similarly strong new issue to justify scope expansion.

## Coverage and verification limits

Reviewed own Go DNS/TUN/reconnect/bootstrap code, Kotlin hysteria wrapper/API/build boundary, app routing persistence/planning/refresh, network/VPN lifecycle, and medium-depth import/update/logging/test conventions. Dependency review focused on Hysteria 2.13.0 integration. This was not an exhaustive whole-app security audit, external dependency vulnerability scan, upstream source audit, accessibility review or performance benchmark. No live Android/network reproduction was performed during planning.

Baseline: pinned-core check passed. `go test -mod=readonly -tags with_gvisor ./...` passed on Darwin arm64 with installed Go 1.27.1 (`ok bedlam/golib 88.919s`), not the pinned release Go 1.26.8. Linux-suffixed stack tests do not execute on Darwin. Android unit command initially lacked SDK configuration; rerun with the installed SDK and pinned NDK reached `:hysteria:buildGolib` but failed fetching the Go checksum record for `github.com/koron/go-ssdp@v0.9.1` with `connect: cannot allocate memory`. Therefore Android unit/build results remain unverified; do not bypass checksum verification or reuse stale native binaries to claim success. No source changes resulted from these checks.
