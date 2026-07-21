package golib

import (
	"crypto/tls"
	"errors"
	"net"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

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

type blockingClient struct {
	tcpErr   error
	tcpDelay time.Duration
}

func (c *blockingClient) TCP(addr string) (net.Conn, error) {
	if c.tcpDelay > 0 {
		time.Sleep(c.tcpDelay)
	}
	return nil, c.tcpErr
}
func (c *blockingClient) UDP() (client.HyUDPConn, error) { return nil, nil }
func (c *blockingClient) Close() error                   { return nil }

func TestDialTCPWithTimeout_timesOut(t *testing.T) {
	c := &blockingClient{tcpDelay: time.Second}
	_, err := dialTCPWithTimeout(c, "example.com:443", 20*time.Millisecond)
	if !errors.Is(err, errDialTimeout) {
		t.Errorf("expected dial timeout, got %v", err)
	}
}

func TestDialTCPWithTimeout_passesThroughError(t *testing.T) {
	c := &blockingClient{tcpErr: coreErrs.DialError{Message: "refused"}}
	_, err := dialTCPWithTimeout(c, "example.com:443", time.Second)
	var de coreErrs.DialError
	if !errors.As(err, &de) {
		t.Errorf("expected dial error passthrough, got %v", err)
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
