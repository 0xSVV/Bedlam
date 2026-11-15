package golib

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/server"
)

func latencyTunnel(t *testing.T, handler http.HandlerFunc, fastOpen bool) (*Session, *httptest.Server) {
	t.Helper()
	web := httptest.NewTLSServer(handler)
	t.Cleanup(web.Close)
	tunnel := newTestTunnel(t, fastOpen, func(string) (net.Conn, error) {
		return net.Dial("tcp", web.Listener.Addr().String())
	})
	return &Session{client: tunnel.reconnectClient, fastOpen: fastOpen}, web
}

func testLatencyProbe(t *testing.T, s *Session) *LatencyProbe {
	t.Helper()
	if p := s.NewLatencyProbe(); p != nil {
		return p
	}
	config, err := s.client.configFunc()
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(clientConfig{Server: config.ServerAddr.String(), TLSInsecure: true, FastOpen: s.fastOpen})
	if err != nil {
		t.Fatal(err)
	}
	p, err := NewProfileLatencyProbe(string(raw), "", nil)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func trustedLatencyProbe(t *testing.T, s *Session, web *httptest.Server) *LatencyProbe {
	p := testLatencyProbe(t, s)
	p.tlsConfig = web.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	return p
}

func TestLatencyHTTPSResponseThroughTunnel(t *testing.T) {
	for _, fastOpen := range []bool{false, true} {
		var requests atomic.Int32
		s, web := latencyTunnel(t, func(w http.ResponseWriter, r *http.Request) {
			requests.Add(1)
			time.Sleep(60 * time.Millisecond)
			w.WriteHeader(http.StatusNoContent)
		}, fastOpen)
		result := trustedLatencyProbe(t, s, web).Run(web.URL+"/generate_204", 3000)
		if !result.Ok || result.ElapsedMs < 50 || requests.Load() != 1 {
			t.Fatalf("fastOpen=%v: result=%+v requests=%d", fastOpen, result, requests.Load())
		}
	}
}

func TestLatencyRejectsTCPRefusalTLSFailureAndHTTPRedirect(t *testing.T) {
	tunnel := newTestTunnel(t, true, func(string) (net.Conn, error) {
		return nil, errors.New("connection refused")
	})
	if r := (&Session{client: tunnel.reconnectClient, fastOpen: true}).NewLatencyProbe().Run("https://example.test/", 1000); r.Ok {
		t.Fatal("a refused TCP connection is not a latency")
	}
	s, web := latencyTunnel(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", "https://must-not-be-contacted.invalid/")
		w.WriteHeader(http.StatusFound)
	}, true)
	if r := s.NewLatencyProbe().Run(web.URL, 1000); r.Ok {
		t.Fatal("untrusted endpoint TLS was accepted")
	}
	if r := trustedLatencyProbe(t, s, web).Run(web.URL, 1000); r.Ok || r.Error != "HTTP 302" {
		t.Fatalf("redirect was followed or accepted: %+v", r)
	}
}

func TestLatencyCancellationKeepsActiveTunnel(t *testing.T) {
	started := make(chan struct{})
	s, web := latencyTunnel(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/hang" {
			close(started)
			<-r.Context().Done()
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}, true)
	p := trustedLatencyProbe(t, s, web)
	done := make(chan *TestResult, 1)
	go func() { done <- p.Run(web.URL+"/hang", 5000) }()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("request did not reach server")
	}
	p.Cancel()
	select {
	case r := <-done:
		if r.Ok || r.Error != "cancelled" {
			t.Fatalf("%+v", r)
		}
	case <-time.After(time.Second):
		t.Fatal("cancel blocked")
	}
	if r := trustedLatencyProbe(t, s, web).Run(web.URL, 1000); !r.Ok {
		t.Fatalf("cancel killed active tunnel: %+v", r)
	}
}

func TestLatencyTimeoutAndFreshRequest(t *testing.T) {
	for _, fastOpen := range []bool{false, true} {
		s, web := latencyTunnel(t, func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/hang" {
				<-r.Context().Done()
				return
			}
			w.WriteHeader(http.StatusNoContent)
		}, fastOpen)
		start := time.Now()
		r := trustedLatencyProbe(t, s, web).Run(web.URL+"/hang", 150)
		if r.Ok || r.Error != "timeout" || time.Since(start) > time.Second {
			t.Fatalf("fastOpen=%v: timeout=%+v after %v", fastOpen, r, time.Since(start))
		}
		if r := trustedLatencyProbe(t, s, web).Run(web.URL, 1000); !r.Ok {
			t.Fatalf("fastOpen=%v: fresh request after timeout failed: %+v", fastOpen, r)
		}
	}
}

func TestLatencyRequestErrorPreservesSocketTimeout(t *testing.T) {
	timeout := &net.OpError{Op: "read", Net: "quic", Err: context.DeadlineExceeded}
	if r := latencyRequestError(timeout); r.Error != "timeout" {
		t.Fatalf("socket deadline lost before the context timer: %+v", r)
	}
	if r := latencyRequestError(errors.New("connection refused")); r.Error != "request" {
		t.Fatalf("non-timeout error misclassified: %+v", r)
	}
}

type rejectLatencyAuth struct{}

func (rejectLatencyAuth) Authenticate(net.Addr, string, uint64) (bool, string) { return false, "" }

func TestLatencyUsesProfileAuthentication(t *testing.T) {
	cert, _ := testCert(t)
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv, err := server.NewServer(&server.Config{
		Conn:          pc,
		TLSConfig:     server.TLSConfig{Certificates: []tls.Certificate{cert}},
		Authenticator: rejectLatencyAuth{},
	})
	if err != nil {
		t.Fatal(err)
	}
	go func() { _ = srv.Serve() }()
	defer srv.Close()
	p, err := NewProfileLatencyProbe(
		"{\"server\":\""+pc.LocalAddr().String()+"\",\"auth\":\"wrong\",\"tls_insecure\":true}", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	r := p.Run("https://example.test/", 3000)
	if r.Ok || r.Error != "connection" {
		t.Fatalf("bad auth accepted: %+v", r)
	}
}

func TestLatencyCancelDuringQUICHandshakeClosesSocket(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	p, err := NewProfileLatencyProbe("{\"server\":\""+pc.LocalAddr().String()+"\",\"tls_insecure\":true}", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan *TestResult, 1)
	go func() { done <- p.Run("https://example.test/", 5000) }()
	packet := make([]byte, 2048)
	_ = pc.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, _, err := pc.ReadFrom(packet); err != nil {
		t.Fatal(err)
	}
	p.Cancel()
	select {
	case r := <-done:
		if r.Ok {
			t.Fatal("cancel succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("handshake cancellation blocked")
	}
	deadline := time.Now().Add(time.Second)
	for len(latencySlots) != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(latencySlots) != 0 {
		t.Fatal("native handshake worker leaked")
	}
}

func TestLatencyCancelledBeforeRunAndInvalidEndpoint(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	p := &LatencyProbe{ctx: ctx, cancel: cancel}
	if r := p.Run("https://example.test/", 100); r.Ok || r.Error != "cancelled" {
		t.Fatalf("%+v", r)
	}
	s := &Session{fastOpen: true}
	if r := s.NewLatencyProbe().Run("http://example.test/", 100); r.Ok {
		t.Fatal("plaintext latency endpoint accepted")
	}
}
