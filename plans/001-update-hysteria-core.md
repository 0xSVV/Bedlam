# 001 — Update the Hysteria core and verify native TUN ownership

Planned at `a35e3890ae00c5d9925f3fb9e40e3761ca149f7d`, 2026-10-14. Priority P1; effort M; risk MED; category migration/correctness. Status: IMPLEMENTED, pending review. No prerequisite implementation plan.

## Outcome and boundaries

Bedlam 1.6.6 embeds Hysteria 2.13.0 at a released commit, with matching dependency and provenance records. Upgrade Bedlam's independently linked sing-tun to 0.9.6 in a separate logical change. Fix the TUN descriptor ownership ambiguity while testing that migration. Do not import upstream CLI configuration or server-only features into the Android app.

The current core pin is `e1366b173ccf5706e1e4630fe8aa654a4b574085` (2.12.3). The verified 2.13.0 target is `ca8fbd874fd6ca82413948de6dc5924405e103e1`. Confirm upstream tags still resolve to that object before implementing. Upstream release: https://github.com/HyNetworks/hysteria/releases/tag/app%2Fv2.13.0.

## Current state and scope

Paths are relative to the repository root, `/Users/shapovalov.v66/Projects/Bedlam`.

- `.github/toolchain.env`: Go 1.26.8, NDK 29.0.14206865, core commit and three 2.12.3 tags.
- `hysteria/golib/go.mod:8–14`: core/extras replacements point at the submodule; quic-go is `v0.62.1-0.20260912175848-73339f7edbb9`, sing is 0.9.4, sing-tun is 0.9.3.
- Hysteria 2.13.0 core/extras require quic-go `v0.63.1-0.20261004180939-a10df75c260c`. Its app module requires sing-tun 0.9.6. Bedlam does not link that app module, so the submodule bump cannot update Bedlam's TUN dependency by itself.
- `hysteria/build.gradle.kts` derives `CORE_VERSION` from the toolchain file and builds all three Android ABIs. `updateHysteriaCore` follows upstream HEAD; do not use it blindly for this released pin.
- `hysteria/src/main/kotlin/ru/shapovalov/hysteria/HysteriaClientImpl.kt:185–208` detaches the Android fd and closes it on every native exception. `hysteria/golib/tun.go:68–87` already closes the TUN on several native errors. sing-tun 0.9.3 wraps the supplied fd in `os.NewFile` and closes it. This permits a double close; simply deleting Kotlin cleanup would instead leak early failures.

Allowed changes: submodule gitlink, `.github/toolchain.env`, `hysteria/golib/go.mod`, `go.sum`, `tun.go`, `tun_gvisor_linux.go`, `HysteriaClientImpl.kt`, TUN API documentation and their focused tests. Change native build configuration only if the selected dependency requires it. Do not edit vendored upstream source, unrelated dependency versions, app UI, DNS policy, QUIC Android timeout defaults, or release version numbers in this package.

## Execution

1. **Check drift and baseline.** Run `git diff --stat a35e389..HEAD -- hysteria .github/toolchain.env`; compare changes with the facts above. Read `README.md` and `.github/actions/setup-build/action.yml`. Use a branch such as `codex/release-1.6.6`; preserve existing untracked `docs/` and `output/`. Run `bash .github/scripts/check-core-pin.sh` and `(cd hysteria/golib && go test -mod=readonly -tags with_gvisor ./...)`. Expected: both exit 0 before changing dependencies. Record toolchain/platform limitations rather than calling unexecuted tests passed.
2. **Pin the released core.** Fetch the needed upstream release tags, verify their peeled commits, check out the exact target in `hysteria/upstream`, and update the toolchain commit plus `app/v2.13.0 core/v2.13.0 extras/v2.13.0`. Keep Go/NDK pins unless compatibility requires a separately explained change. Align quic-go with core/extras and run `go mod tidy` from `hysteria/golib`. Review every module diff. Verify `go list -m all` selects one apernet/quic-go version and `bash .github/scripts/check-core-pin.sh` succeeds.
3. **Give the TUN descriptor one owner.** Prefer a borrowed-fd contract: Kotlin owns its detached fd through the synchronous native call and closes that original in `finally`; native duplicates it before handing a duplicate to sing-tun, owns the duplicate, and closes it exactly once on every failure. Verify actual sing-tun constructor ownership on failure before choosing the cleanup sequence. Use a small test seam for construction/start failures, not a production-wide platform abstraction. An alternative native-takes-ownership contract is acceptable only with proof that all early errors and the binding exception boundary are covered. Run focused ownership tests, then the complete Go suite and Kotlin wrapper tests.
4. **Upgrade sing-tun deliberately.** Set sing-tun 0.9.6, reconcile required sing/gVisor APIs, and regenerate module sums. Inspect upstream's actual module requirements instead of assuming sing 0.9.4 remains compatible. Keep custom DNS interception, `DNSModeDisabled`, gVisor stack choice, TCP/UDP handling and Android-supplied interface behavior. Verify on Linux using `go test -tags with_gvisor ./...`; Darwin cannot exercise Linux-suffixed TUN tests. Run `./gradlew :hysteria:buildGolib` using pinned tools; expected AAR contains arm64-v8a, armeabi-v7a and x86_64 native libraries.
5. **Confirm integration.** Run `./gradlew testDebugUnitTest --stacktrace`, `./gradlew :app:lintRelease --stacktrace`, and `./gradlew :app:assembleRelease --stacktrace`. Confirm About reports 2.13.0. Commit core and TUN changes separately, with imperative messages matching history, for example “Update the Hysteria core to 2.13.0”. Do not publish or tag as part of this plan.

## Required regressions

- TUN failures before construction, after construction, at stack construction, interface start and stack start close exactly the owned descriptor(s); an unrelated descriptor allocated after a close survives subsequent cleanup.
- Normal start/stop and repeated replacement do not leak fds or double-close them. Linux/Android ownership tests must actually execute.
- Existing `tun_gvisor_linux_test.go`, `tun_stack_linux_test.go`, `dns_tunnel_test.go`, `dns_quic_test.go`, `dns_http3_test.go` and reconnect tests pass.
- Device smoke: TCP, UDP relay, port-53 interception, DoQ/HTTP3, IPv6 modes, Wi-Fi/mobile handoff and reconnect. Preserve TLS/ECH/obfuscation profile behavior.

## Done and stop conditions

All commands above exit 0 on a configured runner; changed pins agree; native ownership tests execute; only scoped files changed. Update the plan index with evidence. Stop and report if tag SHAs disagree, ownership remains ambiguous, sing-tun requires a substantially broader stack replacement, or repeated test failures cannot be explained. Do not advertise upstream server sniffing/ACL changes or removal of the CLI `route.strict` option as new Bedlam features. Future TUN upgrades must rerun the ownership suite.
