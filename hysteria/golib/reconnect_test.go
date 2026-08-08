package golib

import (
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"reflect"
	"sync"
	"testing"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/quic-go"
)

type noopClient struct{}

func (noopClient) TCP(addr string) (net.Conn, error)  { return nil, nil }
func (noopClient) UDP() (client.HyUDPConn, error)      { return nil, nil }
func (noopClient) Close() error                        { return nil }

type recordingHandler struct {
	mu     sync.Mutex
	events []string
}

func (h *recordingHandler) OnConnected(udpEnabled bool, attempt int32) { h.add("connected") }
func (h *recordingHandler) OnReconnecting(attempt int32, reason string) { h.add("reconnecting") }
func (h *recordingHandler) OnDisconnected(reason string)                { h.add("disconnected") }

func (h *recordingHandler) add(e string) {
	h.mu.Lock()
	h.events = append(h.events, e)
	h.mu.Unlock()
}

func (h *recordingHandler) snapshot() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]string(nil), h.events...)
}

func TestEmit_dropsSupersededGeneration(t *testing.T) {
	rec := &recordingHandler{}
	rc := &reconnectClient{handler: rec}
	rc.emit(2, func() { rec.add("second") })
	rc.emit(1, func() { rec.add("stale") })
	rc.emit(3, func() { rec.add("third") })
	if got := rec.snapshot(); !reflect.DeepEqual(got, []string{"second", "third"}) {
		t.Errorf("expected superseded emit dropped, got %v", got)
	}
}

func TestEmit_nilHandler(t *testing.T) {
	rc := &reconnectClient{}
	called := false
	rc.emit(1, func() { called = true })
	if called {
		t.Error("fn should not run when handler is nil")
	}
}

func TestMarkDead_skippedWhenFatal(t *testing.T) {
	rec := &recordingHandler{}
	rc := &reconnectClient{handler: rec, inner: noopClient{}}
	rc.fatal.Store(true)
	rc.markDead(errors.New("boom"), "test")
	if got := rec.snapshot(); len(got) != 0 {
		t.Errorf("markDead must not emit once terminal, got %v", got)
	}
	if rc.inner == nil {
		t.Error("markDead must not tear down the client when skipping")
	}
}

func TestNewReconnectClient_initialNonTerminalFailureRetries(t *testing.T) {
	rec := &recordingHandler{}
	cf := func() (*client.Config, error) { return nil, errors.New("network down") }
	rc, err := newReconnectClient(cf, rec, func() (int64, int64) { return 0, 0 }, false)
	if err != nil {
		t.Fatalf("non-terminal initial failure should not fail the session, got %v", err)
	}
	if rc == nil {
		t.Fatal("expected a retrying client")
	}
	defer rc.Close()
	if got := rec.snapshot(); !reflect.DeepEqual(got, []string{"reconnecting"}) {
		t.Errorf("expected an initial reconnecting emit, got %v", got)
	}
}

func TestNewReconnectClient_initialTerminalFailureReturnsError(t *testing.T) {
	rec := &recordingHandler{}
	cf := func() (*client.Config, error) { return nil, coreErrs.AuthError{StatusCode: 401} }
	rc, err := newReconnectClient(cf, rec, func() (int64, int64) { return 0, 0 }, false)
	if err == nil {
		t.Fatal("terminal initial failure should fail the session")
	}
	if rc != nil {
		t.Error("expected nil client on terminal failure")
	}
}

func newTestReconnectClient(cf func() (*client.Config, error), h EventHandler) *reconnectClient {
	return &reconnectClient{
		configFunc:   cf,
		handler:      h,
		stopWatchdog: make(chan struct{}),
		watchdogDone: make(chan struct{}),
	}
}

func TestDial_backoffThrottlesRepeatedFailures(t *testing.T) {
	calls := 0
	cf := func() (*client.Config, error) {
		calls++
		return nil, errors.New("network down")
	}
	rc := newTestReconnectClient(cf, &recordingHandler{})

	if err := rc.dial(false); err == nil {
		t.Fatal("expected first dial to fail")
	}
	if calls != 1 {
		t.Fatalf("expected 1 config attempt, got %d", calls)
	}
	if err := rc.dial(false); !errors.Is(err, errDialBackoff) {
		t.Errorf("expected an immediate re-dial to be throttled, got %v", err)
	}
	if calls != 1 {
		t.Errorf("throttled dial must not attempt a connection, got %d attempts", calls)
	}

	rc.resetBackoff()
	_ = rc.dial(false)
	if calls != 2 {
		t.Errorf("after reset the dial should attempt again, got %d attempts", calls)
	}
}

func TestDial_forceBypassesBackoff(t *testing.T) {
	calls := 0
	cf := func() (*client.Config, error) {
		calls++
		return nil, errors.New("network down")
	}
	rc := newTestReconnectClient(cf, &recordingHandler{})

	_ = rc.dial(false)
	if calls != 1 {
		t.Fatalf("expected first dial to attempt, got %d", calls)
	}
	_ = rc.dial(false)
	if calls != 1 {
		t.Errorf("throttled dial must not attempt, got %d", calls)
	}
	if err := rc.dial(true); err == nil || errors.Is(err, errDialBackoff) {
		t.Errorf("forced dial must bypass backoff, got %v", err)
	}
	if calls != 2 {
		t.Errorf("forced dial should attempt despite backoff, got %d", calls)
	}
}

func TestCurrentClient_backoffSuppressesReconnectingSpam(t *testing.T) {
	cf := func() (*client.Config, error) { return nil, errors.New("network down") }
	rec := &recordingHandler{}
	rc := newTestReconnectClient(cf, rec)

	for i := 0; i < 8; i++ {
		_, _ = rc.currentClient(srcStream)
	}
	n := 0
	for _, e := range rec.snapshot() {
		if e == "reconnecting" {
			n++
		}
	}
	if n != 1 {
		t.Errorf("expected a single reconnecting event under backoff, got %d", n)
	}
}

func TestPoke_nonBlockingAndCoalesces(t *testing.T) {
	rc := &reconnectClient{pokeCh: make(chan struct{}, 1)}
	rc.poke()
	rc.poke()
	select {
	case <-rc.pokeCh:
	default:
		t.Fatal("expected a queued poke")
	}
	select {
	case <-rc.pokeCh:
		t.Error("pokes should coalesce to at most one")
	default:
	}
}

func TestCheckNow_redialsWhenDown(t *testing.T) {
	calls := 0
	cf := func() (*client.Config, error) {
		calls++
		return nil, errors.New("network down")
	}
	rc := newTestReconnectClient(cf, &recordingHandler{})
	rc.checkNow()
	if calls != 1 {
		t.Errorf("checkNow should re-dial when there is no live client, got %d attempts", calls)
	}
}

func TestMarkDead_emitsWhenLive(t *testing.T) {
	rec := &recordingHandler{}
	rc := &reconnectClient{handler: rec, inner: noopClient{}}
	rc.markDead(errors.New("boom"), "test")
	if got := rec.snapshot(); !reflect.DeepEqual(got, []string{"reconnecting"}) {
		t.Errorf("expected one reconnecting emit, got %v", got)
	}
}

func TestIsTerminal_echRejection(t *testing.T) {
	err := coreErrs.ConnectError{Err: &tls.ECHRejectionError{}}
	if !(&reconnectClient{}).isTerminal(err) {
		t.Error("expected ECH rejection to be terminal")
	}
}

func TestIsTerminal_certFailureWithECH(t *testing.T) {
	err := coreErrs.ConnectError{
		Err: &tls.CertificateVerificationError{Err: errors.New("bad cert")},
	}
	if !(&reconnectClient{echConfigured: true}).isTerminal(err) {
		t.Error("expected cert failure to be terminal when ECH is configured")
	}
	if (&reconnectClient{}).isTerminal(err) {
		t.Error("expected cert failure to stay retryable without ECH")
	}
}

func TestIsTerminal_authAndConfig(t *testing.T) {
	rc := &reconnectClient{}
	if !rc.isTerminal(coreErrs.AuthError{}) {
		t.Error("expected auth error to be terminal")
	}
	if !rc.isTerminal(coreErrs.ConfigError{Field: "x", Reason: "bad"}) {
		t.Error("expected config error to be terminal")
	}
	if rc.isTerminal(errors.New("transient")) {
		t.Error("expected plain error to be retryable")
	}
}

func TestIsTunnelDead(t *testing.T) {
	streamLimit := &quic.StreamLimitReachedError{}
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"closed", coreErrs.ClosedError{}, true},
		{"closed wrapped", fmt.Errorf("relay: %w", coreErrs.ClosedError{Err: io.EOF}), true},
		{"connect", coreErrs.ConnectError{}, true},
		{"connect wrapped", fmt.Errorf("dial: %w", coreErrs.ConnectError{}), true},
		{"dial refused by server", coreErrs.DialError{Message: "UDP not enabled"}, false},
		{"dial wrapped", fmt.Errorf("udp: %w", coreErrs.DialError{}), false},
		{"stream limit reached", streamLimit, false},
		{"stream limit wrapped", fmt.Errorf("open: %w", streamLimit), false},
		{"unknown", errors.New("boom"), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isTunnelDead(tc.err); got != tc.want {
				t.Fatalf("isTunnelDead(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}

func TestProbeIndicatesDead(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"timeout", errDNSTimeout, true},
		{"timeout wrapped", fmt.Errorf("probe: %w", errDNSTimeout), true},
		{"deadline exceeded", os.ErrDeadlineExceeded, true},
		{"closed", coreErrs.ClosedError{}, true},
		{"read failed", fmt.Errorf("read response length: %w", io.EOF), true},
		{"dial refused by server", coreErrs.DialError{Message: "UDP not enabled"}, false},
		{"malformed length", fmt.Errorf("%w: invalid response length: 0", errDNSMalformed), false},
		{"malformed txid", fmt.Errorf("%w: response transaction ID mismatch", errDNSMalformed), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := probeIndicatesDead(tc.err); got != tc.want {
				t.Fatalf("probeIndicatesDead(%v) = %v, want %v", tc.err, got, tc.want)
			}
		})
	}
}
