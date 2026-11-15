package golib

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLatencyStalledTCPHandshakeReleasesSlots(t *testing.T) {
	for _, cancelProbe := range []bool{false, true} {
		name := "timeout"
		if cancelProbe {
			name = "cancel"
		}
		t.Run(name, func(t *testing.T) {
			web := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(http.StatusNoContent)
			}))
			t.Cleanup(web.Close)
			release := make(chan struct{})
			entered := make(chan struct{}, cap(latencySlots))
			tunnel := newTestTunnel(t, false, func(addr string) (net.Conn, error) {
				if addr == "blocked.test:443" {
					entered <- struct{}{}
					<-release
					return nil, errors.New("fixture released")
				}
				return net.Dial("tcp", web.Listener.Addr().String())
			})
			defer close(release)
			config, err := tunnel.configFunc()
			if err != nil {
				t.Fatal(err)
			}
			raw, err := json.Marshal(clientConfig{Server: config.ServerAddr.String(), TLSInsecure: true})
			if err != nil {
				t.Fatal(err)
			}
			active, err := NewSession(string(raw), "", nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { _ = active.Close() })
			if active.NewLatencyProbe() != nil {
				t.Fatal("a session without Fast Open must require an owned probe")
			}

			for i := 0; i < cap(latencySlots); i++ {
				probe := testLatencyProbe(t, active)
				if !probe.owned {
					t.Fatal("fallback did not own its session")
				}
				timeout := int64(500)
				if cancelProbe {
					timeout = 5000
				}
				done := make(chan *TestResult, 1)
				go func() { done <- probe.Run("https://blocked.test/", timeout) }()
				select {
				case <-entered:
				case <-time.After(3 * time.Second):
					t.Fatal("TCP request did not reach the server")
				}
				want := "timeout"
				if cancelProbe {
					probe.Cancel()
					want = "cancelled"
				}
				select {
				case result := <-done:
					if result.Error != want {
						t.Fatalf("result=%+v, want %s", result, want)
					}
				case <-time.After(time.Second):
					t.Fatal("probe did not stop")
				}
				deadline := time.Now().Add(time.Second)
				for len(latencySlots) != 0 && time.Now().Before(deadline) {
					time.Sleep(time.Millisecond)
				}
				if got := len(latencySlots); got != 0 {
					t.Fatalf("%d native workers retained their slots", got)
				}
			}

			if result := trustedLatencyProbe(t, active, web).Run(web.URL, 3000); !result.Ok {
				t.Fatalf("fresh latency check failed after stalled probes: %+v", result)
			}
			conn, err := active.currentClient().TCP("healthy.test:443")
			if err != nil {
				t.Fatalf("probe cancellation interrupted the active VPN: %v", err)
			}
			_ = conn.Close()
		})
	}
}
