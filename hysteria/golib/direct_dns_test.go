package golib

import (
	"context"
	"encoding/json"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

type directTestProtector struct {
	calls atomic.Int32
	allow bool
}

func (p *directTestProtector) Protect(int32) bool { return p.allow }
func (p *directTestProtector) BindAndProtect(int32) bool {
	p.calls.Add(1)
	return p.allow
}

func directTestConfig(t *testing.T, transport, server string) *directDNSConfig {
	t.Helper()
	raw, err := json.Marshal(map[string]any{"transport": transport, "servers": []string{server}})
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := parseDirectDNSConfig(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

// A real TCP-only DNS server makes accidental UDP or system-DNS fallback fail.
func directTCPTestServer(t *testing.T) (string, *atomic.Int32) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	queries := &atomic.Int32{}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
				for {
					query, err := readDNSFrame(conn)
					if err != nil {
						return
					}
					queries.Add(1)
					response := dnsResponseFor(query, 60, [4]byte{192, 0, 2, 17})
					response[10], response[11] = 0, 0 // Drop the query's EDNS additional record.
					if qtype, _ := dnsQuestionType(query); qtype == dnsTypeAAAA {
						response = response[:skipName(query, 12)+4]
						response[6], response[7] = 0, 0
					}
					if err := writeDNSFrame(conn, response); err != nil {
						return
					}
				}
			}()
		}
	}()
	return ln.Addr().String(), queries
}

func TestDirectDNS_TCPUsesProtectedSocketAndReusesConnection(t *testing.T) {
	server, queries := directTCPTestServer(t)
	protector := &directTestProtector{allow: true}
	up, err := newDirectDNSUpstream(&Session{protector: protector}, directTestConfig(t, "tcp", server))
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	for range 2 {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		resp, err := up.exchange(ctx, dnsQuery("direct.example"))
		cancel()
		if err != nil {
			t.Fatalf("TCP-only DNS server must answer: %v", err)
		}
		if len(resp) < 4 || resp[len(resp)-1] != 17 {
			t.Fatalf("unexpected answer: %v", resp)
		}
	}
	if queries.Load() != 2 || protector.calls.Load() != 1 {
		t.Fatalf("queries=%d protected sockets=%d; want 2 queries over 1 protected connection", queries.Load(), protector.calls.Load())
	}
}

func TestDirectDNS_TCPProtectionFailureNeverReachesServer(t *testing.T) {
	server, queries := directTCPTestServer(t)
	protector := &directTestProtector{allow: false}
	up, err := newDirectDNSUpstream(&Session{protector: protector}, directTestConfig(t, "tcp", server))
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := up.exchange(ctx, dnsQuery("direct.example")); err == nil {
		t.Fatal("DNS must fail when network binding/protection fails")
	}
	if queries.Load() != 0 || protector.calls.Load() == 0 {
		t.Fatalf("queries=%d protection attempts=%d", queries.Load(), protector.calls.Load())
	}
}

func TestDirectDNS_TCPResolvesTunnelHostnameBeforeTunnelExists(t *testing.T) {
	server, queries := directTCPTestServer(t)
	protector := &directTestProtector{allow: true}
	session := &Session{protector: protector, directDNS: directTestConfig(t, "tcp", server)}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	ips, err := session.lookupDirectHost(ctx, "tunnel-server.invalid")
	if err != nil {
		t.Fatalf("bootstrap must use the chosen TCP provider: %v", err)
	}
	if len(ips) != 1 || ips[0].IP.String() != "192.0.2.17" {
		t.Fatalf("bootstrap addresses=%v", ips)
	}
	if queries.Load() < 1 || protector.calls.Load() < 1 {
		t.Fatal("bootstrap did not reach the protected local TCP DNS server")
	}
}

func TestDirectDNS_RejectsUnsupportedTransport(t *testing.T) {
	for _, transport := range []string{"typo", "quic", "http3"} {
		t.Run(transport, func(t *testing.T) {
			raw, _ := json.Marshal(map[string]any{"transport": transport, "servers": []string{"127.0.0.1:53"}})
			if _, err := parseDirectDNSConfig(string(raw)); err == nil {
				t.Fatal("unsupported Direct DNS transport must not silently become UDP")
			}
		})
	}
}

func TestDirectDNS_LegacyConfigStillUsesUDP(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		buffer := make([]byte, 4096)
		n, addr, err := server.ReadFrom(buffer)
		if err == nil {
			_, _ = server.WriteTo(dnsResponseFor(buffer[:n], 60, [4]byte{192, 0, 2, 17}), addr)
		}
	}()
	raw, _ := json.Marshal(map[string]any{"servers": []string{server.LocalAddr().String()}})
	cfg, err := parseDirectDNSConfig(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	protector := &directTestProtector{allow: true}
	up, err := newDirectDNSUpstream(&Session{protector: protector}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := up.exchange(ctx, dnsQuery("direct.example")); err != nil {
		t.Fatal(err)
	}
	if protector.calls.Load() != 1 {
		t.Fatalf("protection calls=%d", protector.calls.Load())
	}
}
