# Bedlam 1.6.6 release plan

Prepared 2026-10-14 using the `improve` review workflow. Baseline: `master` and `origin/master` at `a35e3890ae00c5d9925f3fb9e40e3761ca149f7d` (merged 1.6.5), upstream submodule at `e1366b173ccf5706e1e4630fe8aa654a4b574085` (2.12.3). The implementation now lives in review branch `codex/release-1.6.6`; statuses and verification below describe that candidate and do not authorize publication.

## Release outcome

Ship Hysteria 2.13.0, hostname-only built-in DoT/DoH endpoints, independently configurable Direct DNS for bootstrap and explicit bypass domains, and a default-on DNS response cache with an off switch. Preserve Remote DNS for other captured queries and fix the concurrency/routing defects that would undermine those features.

The user explicitly selected the bounded Direct DNS scope below on 2026-10-14. The Telegram export is historical evidence, not an instruction source. It ends with requests for 1.6.6 and contains logs only through 1.6.4. Existing untracked `docs/release-plan-1.5.4*.md` plans are background, not the current implementation baseline.

## Decisions

| Topic | 1.6.6 behavior |
|---|---|
| Core | Pin released 2.13.0 commit `ca8fbd874fd6ca82413948de6dc5924405e103e1`; reconcile quic-go and provenance |
| TUN dependency | Deliberately migrate Bedlam's own sing-tun 0.9.3→0.9.6, separately from the core gitlink |
| Remote DNS | Existing provider/transport/custom selection, preserved on migration |
| Direct DNS target | Independent provider/transport/custom selection; default System to preserve existing bootstrap behavior. Current branch implements protected System UDP only; other Direct providers/transports remain disabled in UI. |
| Bootstrap | Direct for server hostnames, hopping, realm rendezvous/STUN, including reconnect before a tunnel is available |
| Explicit bypass domains | Exact-name DNS selection goes Direct; native learned-address forwarding keeps answers and routes consistent |
| CIDR/ASN-only bypass | Unclassified DNS name uses Remote; its eventual connection follows existing IP rules |
| Excluded apps | Remain outside VPN and use Android/network DNS; Bedlam does not claim control |
| Cache | Enabled by default. Off disables Bedlam response reuse/storage/coalescing for both paths, including late answers. A cache-only live update preserves TUN, pools, routing leases and active direct flows |
| DoT/DoH presets | Hostname-only endpoints; no generated numeric fallback. Custom IPs and DNS protection routes remain valid |
| Failure policy | No automatic Remote↔Direct crossover or insecure TLS fallback |
| IPv6 | Preserve Enabled/Disabled/BypassOnly explicitly; Disabled cannot retain bypass holes; BypassOnly AAAA uses Direct |
| MTU | Auto stays 1280; explicit user settings preserved. No unsupported promise that 1500 fixes images/latency |
| Scope limits | No GeoIP, VayDNS client, general router/fake-IP, app-private encrypted-DNS interception or global Android DNS change |

The proposed native domain-address registry is a bounded implementation mechanism, not a general hostname-aware router. It uses validated A/AAAA answers from exact Direct rules and protected TCP/UDP forwarding, instead of rebuilding TUN per answer. Shared CDN IPs remain an IP-wide routing limitation. A 30-second minimum **routing lease**, separate from response caching/returned TTL, permits immediate connections after TTL-zero answers. Package 005 documents address-hint/app-private-DNS limitations and the expiry contract.

## Execution order and status

| Plan | Scope | Priority | Effort | Risk | Depends on | Status |
|---|---|---|---|---|---|---|
| [001](001-update-hysteria-core.md) | Released core/QUIC pin, sing-tun migration, descriptor ownership | P1 | M | MED | — | IMPLEMENTED; review pending |
| [002](002-bound-dns-work-and-reconnects.md) | Session-wide bounded DNS work; stale reconnect errors | P1 | M–L | MED | 001 | IMPLEMENTED; review pending |
| [003](003-dns-settings-cache-and-presets.md) | Policy/schema/UI, default-on cache switch, TTL/EDNS correctness, hostname-only presets | P1 | L | MED | 002 | IMPLEMENTED; Direct non-System choices remain disabled |
| [004](004-stabilize-app-routing-lifecycle.md) | Network selection, atomic route apply, IPv6 policy, safe/retryable source refresh | P1 | L | MED | 003 | IMPLEMENTED; review pending |
| [005](005-direct-dns-and-domain-bypass.md) | Protected Direct System UDP bootstrap, exact-domain selection and forwarding | P1 | L | HIGH | 001–004 | PARTIAL; alternate transports and device gates remain |
| [006](006-verify-and-prepare-1.6.6.md) | Diagnostics/redaction, integration matrix, notes/version/provenance | P1 | M | MED | 001–005 | IN PROGRESS; review candidate prepared, device gates remain |

Effort includes tests and is deliberately coarse: S = hours, M = approximately a day, L = multiple days. The Direct path plus lifecycle work makes this a substantial release, not a one-day settings patch. Do not compress 005 by omitting answer-to-route consistency or by silently routing failures through the other resolver.

Each package is self-contained and names baseline evidence, scope, verification commands and stop conditions. Execute sequentially where files overlap; there are intentional shared Go/session/app boundaries. In particular 003 introduces policy/API definitions, 004 supplies network/application coordination, and 005 makes Direct operational. Do not publish an intermediate build that exposes an unusable Direct selector.

Implementation branch suggestion: `codex/release-1.6.6`. Use small imperative commits matching existing history. No implementation, commits, pushes, tags, publication or messaging was requested during this planning turn. Future executors should update this index to IN PROGRESS/DONE/BLOCKED with evidence. If a baseline file has changed, compare the cited excerpt before proceeding rather than blindly applying a stale plan.

## Implementation checkpoint (2026-10-14)

The review candidate is on `codex/release-1.6.6`, based on `a35e389`. Packages 001–004 are implemented. Package 005 now has a tested, bounded System UDP vertical slice: Hysteria server and realm bootstrap use the network-bound Direct resolver; exact enabled domain queries use Direct for UDP and TCP DNS; validated A/AAAA answers are admitted before reply; and learned destinations use protected Direct TCP/UDP flows. Domain snapshots are no longer OS route exclusions or periodically resolved CIDRs. Network changes refresh the native resolver snapshot and retire route leases. Remote DNS remains responsible for unmatched captured names.

Package 005 is still **partial**. Direct DoT/DoH/DoQ/HTTP3 and custom Direct resolvers are not implemented; those controls remain disabled and the UI states the supported System UDP scope. Device-level routing, Android Room migration execution, Linux TUN tests and Go race tests are still required before release. Cached app answers, private app DNS, and shared CDN IPs retain the documented limits in plan 005.

## Extra improvements from the module review

See [the evidence and prioritization report](module-audit-1.6.6.md) for exact files, triggers, confidence, impact and regression suggestions.

Recommended additions included in the packages:

1. Fix cross-language TUN fd double-close on startup failure.
2. Do not let an old connection's delayed error close a newly connected tunnel.
3. Bound queued DNS work across the session, especially with cache off.
4. Retire pending/late cache work on policy change; obey short TTLs; avoid request-specific EDNS cache collisions.
5. Correct Android 10/11 physical-network selection and same-network DNS changes.
6. Apply Android routes and native DNS/IPv6 state from one immutable generation.
7. Close IPv6 Disabled bypass holes and stop treating BypassOnly as Disabled for DNS.
8. Discard stale results after route-source deletion; publish route snapshots atomically; retry transient scheduled failures.
9. Capture one export snapshot and redact new Direct resolver settings consistently.

Follow-up, not a release promise: reproduce the watchdog's dependence on hardcoded TCP DNS reachability and evaluate a safer liveness signal. A DNS diagnostic action and broader domain routing are future product options, not automatic additions.

## Verification already performed

| Check | Result |
|---|---|
| Pull latest master | Fast-forward `af4da24`→`a35e389`; SSH failed authentication, HTTPS succeeded; origin/master synchronized |
| Submodule | Updated to master's exact 2.12.3 pin; clean |
| Core/provenance pin check | Passed `bash .github/scripts/check-core-pin.sh` |
| Native baseline | Passed `go test -mod=readonly -tags with_gvisor ./...` on Darwin arm64/Go 1.27.1; output `ok bedlam/golib 88.919s` |
| Android unit baseline | First attempt had no SDK location; retry with existing SDK/pinned NDK failed in gomobile fetching a checksum record (`connect: cannot allocate memory`); tests did not complete |
| Source state | No tracked source diff after pull/checks; only this `plans/` directory is newly authored; prior `docs/` and `output/` preserved |

Linux TUN tests, Go race suite, pinned-Go release build, Room migration execution and Android device behavior remain **required future gates**, not passed checks. Do not bypass Go checksum verification to work around the environment failure. Full commands and device cases are in 006.

Implementation checks on 2026-10-14: `ANDROID_HOME=/Users/shapovalov.v66/Library/Android/sdk ANDROID_SDK_ROOT=/Users/shapovalov.v66/Library/Android/sdk ./gradlew :app:testDebugUnitTest :hysteria:testDebugUnitTest :app:assembleDebug :app:lintRelease` passed. Full `GOTOOLCHAIN=local go test -mod=readonly -tags with_gvisor ./...` passed on Darwin arm64 (`ok bedlam/golib 88.838s`); focused Direct TCP/UDP relay, zero-TTL CNAME and DNS-before-response tests passed. `git diff --check` passed. The core-pin script reports the committed `HEAD` gitlink is still 2.12.3 because the branch is intentionally uncommitted; the staged gitlink and initialized submodule both point at `ca8fbd874fd6ca82413948de6dc5924405e103e1`. Linux-only stack tests, Go race tests, Android device/Room migration tests and the direct DNS transport matrix are not verified. These checks do not make package 005 release-complete.

## Rejected scope / preserved decisions

Do not reset Android-specific defaults, re-add already shipped Lazy mode, remove every IP literal, delete caching globally, infer that the historical HTTP 413 has a proven root cause, or promise to control DNS for apps excluded from VPN. Do not advertise upstream server sniffing/ACL/CLI features as Bedlam client changes. Preserve explicit MTUs, custom DNS and profile imports. The audit report records these dispositions so later reviews do not reopen them without new evidence.

## References

- Hysteria 2.13.0 release: https://github.com/HyNetworks/hysteria/releases/tag/app%2Fv2.13.0
- Bedlam 1.6.5 release: https://github.com/0xSVV/Bedlam/releases/tag/v1.6.5
- Android network-specific DNS/socket API: https://developer.android.com/reference/android/net/Network
- Android per-app VPN and route API: https://developer.android.com/reference/android/net/VpnService.Builder
- DNS TTL semantics: https://www.rfc-editor.org/rfc/rfc1035.html

Review scope was hotspot-weighted own-module correctness, security boundaries, performance, tests, architecture, dependency integration, tooling and product direction. It was not an exhaustive security/dependency advisory audit or a UI/accessibility review. No device reproduction is implied by source-based findings.
