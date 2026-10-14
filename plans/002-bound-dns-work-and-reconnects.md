# 002 — Bound DNS work and reject failures from retired tunnels

Planned at `a35e3890ae00c5d9925f3fb9e40e3761ca149f7d`, 2026-10-14. Priority P1; effort M–L; risk MED; category correctness/performance. Status: IMPLEMENTED, pending review. Execute after 001 to avoid concurrent native writers. These are recommended release additions found during the module review.

## Why

Turning caching off increases real DNS exchanges. Today a slow resolver can accumulate unbounded waiting goroutines despite the apparent 64-query limit. Separately, an error returned from an old Hysteria client can close a healthy replacement client. Both undermine the intended low-speed DNS and network-switch behavior.

## Evidence and scope

- `hysteria/golib/tun.go:298`: semaphore is per UDP packet connection. Lines 339–350 start a goroutine before waiting for admission and start the timeout after the wait.
- `hysteria/golib/dns_cache.go:66–77`: `singleflight.Do` detaches shared work from the caller and provides a fresh full budget. Admission/deadline changes must account for this nested work, not just wrap a caller timeout around it.
- `hysteria/golib/reconnect.go:207–219`: `markDead` closes the current `rc.inner` without identifying the client that failed. TCP at 295–301, UDP at 308–314, `checkNow` at 368–383 and `probe` at 457–461 can report errors from an earlier client.
- Existing test conventions: `recordingHandler`/fake clients in `reconnect_test.go`; `newFakePacketConn`, `stubResolver`, controlled channels and DNS fixtures in `dns_cache_test.go` and `tun_test.go`.

In scope: `tun.go`, `session.go`, `dns_cache.go` only for deadlines/admission, `reconnect.go`, small new `dns_scheduler.go`/test if useful, and focused native tests. Out of scope: cache policy/key semantics (003), DNS transport refactor (005), QUIC timers, keepalive defaults and general watchdog redesign.

## Steps and verification

1. **Establish the failures with deterministic tests.** Check `git diff --stat a35e389..HEAD -- hysteria/golib`; read changes from 001. Use barriers/channels, not long sleeps. Write stale A → new B → delayed A error tests for TCP, UDP and watchdog. For admission, hold the resolver and send misses across multiple UDP flows; expose scheduler counters for test assertions rather than relying on noisy process-wide goroutine counts. Run `(cd hysteria/golib && go test -run 'Test.*(Reconnect|MarkDead|DNS.*Admission|DNS.*Queue)' ./...)`; the new failure assertions must fail on the unfixed code for the intended reason.
2. **Separate conditional failure from intentional reset.** Carry the captured client or session identity through every operation/probe; compare under `rc.mu` before retiring the client. Preserve explicit user/network reset as an unconditional operation. Do not assume the underlying interface value is always comparable; a monotonic connection token is acceptable. Increment and compare identity atomically with publishing `rc.inner`. Verify all new stale-error cases pass and current-client failures still emit one reconnect transition. Existing fatal/auth/lazy/backoff tests must remain green.
3. **Add session-wide bounded DNS admission.** Keep validation/local AAAA handling and eligible cache hits ahead of admission. Use explicit constants, initially 64 executing exchanges and 128 queued queries per session across UDP/TCP ingress, with test-injectable limits. This is a starting bound, not a performance guarantee; record device results. Do not create an unbounded goroutine per queued query. Full queue returns bounded SERVFAIL for a valid query, without dropping cached/local responses behind the queue. Account for shared exchanges and resolver failover children; active work must remain bounded after a caller times out. Start the end-to-end query deadline at reception. Bound any deliberate late-answer work separately and terminate it on session shutdown. Verify a flood across multiple flows never exceeds either count and shutdown drains work.
4. **Preserve transport semantics.** TCP still honors framing, UDP responses keep source/transaction identity, non-DNS payloads are rejected locally, and a queue overflow never opens a direct fallback. Validate cache-off and cache-on traffic with the same scheduler. Run `(cd hysteria/golib && go test -mod=readonly -tags with_gvisor ./...)` and `go test -race -tags with_gvisor ./...` in that directory on the supported Linux runner. Expected: exit 0, no races, no leaked pending tasks in fixtures.

## Test matrix and done criteria

- Burst larger than active+pending capacity from multiple UDP source ports and TCP clients; invalid packets do not consume exchange slots; cache hits still complete while misses stall.
- Queue time consumes the caller budget; expired queued requests never start an upstream exchange.
- Shared exchange cancellation does not strand other legitimate waiters; shutdown cancels detached work and late callbacks.
- A fails after B is published: B remains open and no new reconnect event appears. Current B fails: one normal transition. Explicit reset still replaces B. Lazy mode does not eagerly connect.
- Go suite and race suite pass; selected bounds and overload behavior are documented; `git diff --check` passes. Update `plans/README.md`.

## Stop and maintenance

Stop if existing resolver cancellation cannot be bounded without changing its public lifecycle contract; report the required API change before broadening the refactor. Do not treat increasing timeouts as an admission fix. The hardcoded watchdog DNS target is a separate investigation in the audit report; do not weaken dead-tunnel detection as a side effect of these changes. Future transports must participate in the same scheduler and carry the same client generation.
