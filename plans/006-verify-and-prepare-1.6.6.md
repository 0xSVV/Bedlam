# 006 — Verify the integrated release and prepare 1.6.6

Planned at `a35e3890ae00c5d9925f3fb9e40e3761ca149f7d`, 2026-10-14. Priority P1; effort M; risk MED; category tests/release. Status: IN PROGRESS; local unit/build/lint gates pass, while 005 transport coverage and device/Linux gates remain. Depends on 001–005. This prepares a reviewable release; it does not authorize publishing, signing with new credentials, or sending messages to the Telegram contact.

## Scope and current state

Bedlam is currently 1.6.5, `versionCode=29` in `gradle/libs.versions.toml:4–5`. APK output codes are base×10 plus ABI digit; universal uses 9 (`app/build.gradle.kts`). New release uses 1.6.6 and base code 30, unless a later release has already consumed those values. The Hysteria core is recorded in `.github/toolchain.env`, checked by `.github/scripts/check-core-pin.sh`, and exposed in About through the hysteria library.

Allowed source changes in this package: `gradle/libs.versions.toml`, `release-notes/1.6.6.md`, `README.md`, narrowly scoped DNS diagnostics/redaction changes in `app/.../feature/logs/data`, and focused release/log tests. Test/CI additions needed to actually execute Linux native and Android migration checks are allowed. No new networking behavior; failures return to their owning package. Verification evidence may be stored under `plans/verification-1.6.6.md` with secrets and private traffic redacted.

## Preconditions and exact gates

Use Java 21, Android SDK 37, NDK 29.0.14206865 and the Go pin from `.github/toolchain.env` (baseline 1.26.8). Install gomobile/gobind at the `golang.org/x/mobile` version selected by `hysteria/golib/go.mod`, following `.github/actions/setup-build/action.yml`; do not use arbitrary globally installed versions for release evidence.

Run from repository root unless noted:

| Gate | Command | Required result |
|---|---|---|
| Core pin | `bash .github/scripts/check-core-pin.sh` | Clean checkout matches 2.13.0 commit and all three tags |
| Go tests (Linux) | `cd hysteria/golib && go test -mod=readonly -tags with_gvisor ./...` | Exit 0; Linux-suffixed stack tests actually execute |
| Go races (Linux) | `cd hysteria/golib && go test -race -tags with_gvisor ./...` | Exit 0, no race reports |
| Kotlin tests | `./gradlew testDebugUnitTest --stacktrace` | All app/hysteria tests pass |
| Android/Room integration | `./gradlew connectedDebugAndroidTest --stacktrace` after 003/004 add instrumentation harness, if used | Actual migration/DAO tests execute on device; no empty-suite success |
| Script lint | `shellcheck --severity=warning .github/scripts/*.sh` | Exit 0 |
| Android lint | `./gradlew :app:lintRelease --stacktrace` | Exit 0; inspect any new warnings |
| APK build | `./gradlew :app:assembleRelease --stacktrace` | Per-ABI + universal artifacts with expected codes/native libs |
| Provenance | `bash .github/scripts/write-provenance.sh build/release` | Manifests match APK digests, core/dependency/toolchain pins |
| Patch hygiene | `git diff --check` | Exit 0; only planned files changed |

Do not run `check-release-version.sh v1.6.6` as a pre-tag gate: it requires that tag already exist at origin and point at HEAD. The publish workflow runs it after an authorized tag exists. Before publication, validate catalog/notes/base code locally with the existing release tests and review the intended tag commit. Do not create a tag merely to make the check pass.

## Steps

1. **Reconcile all package evidence.** `git diff --stat a35e389..HEAD -- app hysteria .github gradle README.md release-notes` must map to packages 001–005. Verify no old 1.5.4 plan has been mistaken for current source truth. Resolve every release-critical pending case before proceeding. The three old Telegram screenshots and logs through 1.6.4 are historical evidence; they do not prove a 1.6.5 regression.
2. **Make DNS reports diagnosable.** In `feature/logs/data/LogExporter.kt:25–36`, capture one routing snapshot for both header and redaction rather than two `routing.get()` reads. Extend `LogExportSummary.kt` and redaction rules/tests to cover Direct and Remote selections, cache flag, effective policy generation and non-sensitive network category. Redact custom Direct endpoint/host details under “Hide addresses”; never export queried domain histories, credentials or new raw identifiers by default. Keep direct bytes separate from tunnel bytes. Verify `LogExporterTest`, `LogRedactionTest`, summary fixtures and the full Kotlin suite.
3. **Prepare metadata and prose before the release build.** Set versionName 1.6.6/base code 30 after checking latest release drift. Add concise release notes that distinguish core update, Direct scope, hostname-only preset trade-off, cache switch and verified correctness fixes. Update README routing/cache/core/build descriptions. Retain MTU Auto=1280 and existing explicit MTUs; changing it was mentioned in the chat but is not selected for this release plan. Do not claim universal fresh DNS, no DNS errors, control of excluded apps, upstream server features, or all-domain routing via app-private DoH/SVCB hints. Review the intended final source/metadata state before building artifacts.
4. **Run the full automated gates on that final state.** Record commands, platform, exact tool versions, commit and results. Do not hide network/build environment failures behind stale AARs or skipped native work. If this host cannot build, use the configured Linux CI and state that evidence separately; a green Darwin Go run does not replace Linux TUN or Android evidence. APKs, version checks and provenance must all describe the same 1.6.6 source/metadata state. Any subsequent source/version/bundled-README change requires rebuilding affected artifacts and regenerating their provenance.
5. **Run the device matrix below against those artifacts.** Use controlled DNS responders and a test Hysteria server so you can distinguish “wrong route” from “upstream unavailable.” Collect counters/packet captures with synthetic test names rather than user traffic. Each case needs expected/observed outcome and artifact/build identity.
6. **Produce a reviewable result.** Record known limitations, evidence, and any deferred audit finding. Review diff and APK provenance; update plan statuses. No publishing, pushing or Telegram messaging is part of this planning request.

## Device acceptance matrix

| Dimension | Cases and required observations |
|---|---|
| Android versions | API29/30, API33+; one physical device where possible; both route implementations |
| Network | Wi-Fi, mobile, both present, unused network lost, handoff, captive/no network, same-network DNS change |
| Bootstrap | Hostname server, literal IPv4/IPv6 server, port hopping, realm rendezvous/STUN; cold start, old TUN during reconnect, lazy mode |
| DNS transports | UDP/TCP/DoT/DoH for presets; custom DoQ/HTTP3; IPv4-only and IPv6 resolver reachability |
| DNS routing | Exact bypass name → Direct; unmatched and IP/ASN-only names → Remote; excluded app → Android; hardcoded port-53 resolver still intercepted for captured apps |
| Hostname-only failure | Server cannot resolve/reach Remote hostname; clear failure, no numeric/Direct fallback, TLS validation still active |
| Caching | On/off, negative answer, TTL 0/1/4, concurrent duplicates, late response during off/config/network transition; TLS pools retained |
| Direct addresses | CDN answer changes, CNAME chain, TTL-zero connection, lease expiry, registry full, shared IP owners, rule removal; no TUN rebuild per answer |
| IPv6 | Enabled normal behavior; Disabled no payload bypass even with IPv6 CIDR/LAN rules; protected IPv6 server still connects; BypassOnly AAAA available through Direct |
| Stress | Slow/offline DNS, bursts beyond scheduler limits, concurrent network/settings changes, stale old-client errors; bounded memory/work, no false teardown of new client |
| Upgrades | Existing 1.6.5 custom Remote settings, cache default on, Direct default System, profiles/routes/MTU retained; per-ABI and universal install continuity |
| Logging | Hidden-address export redacts both resolver selections consistently, with enough policy information to debug |

## Release completion conditions

Every required automated gate and device category has evidence or an explicit unresolved release blocker. All implemented claims match verified behavior. No new secrets in logs/plans/provenance. Stop for migration data loss, direct/remote crossover, unprotected sockets, unexplained fd errors, IPv6 Disabled bypass, unbounded DNS work, inconsistent Android/native policies, or failed signed-upgrade compatibility. An unresolved optional watchdog investigation may be documented; it must not be reported fixed without a reproducer and evidence.
