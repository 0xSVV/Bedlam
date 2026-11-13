package golib

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func encryptedDirectConfig(t *testing.T, transport, endpoint string, bootstrap map[string][]string) *directDNSConfig {
	t.Helper()
	raw, err := json.Marshal(map[string]any{
		"transport": transport,
		"servers": []string{endpoint},
		"bootstrap": bootstrap,
	})
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := parseDirectDNSConfig(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	return cfg
}

func TestDirectDNS_DoTBootstrapsWithoutResolvingCertificateName(t *testing.T) {
	cert, roots := testCert(t)
	server := newRecordingDoTServer(t, cert)
	_, port, _ := net.SplitHostPort(server.addr)
	cfg := encryptedDirectConfig(t, "tls", "dns.test:"+port, map[string][]string{"dns.test": {"127.0.0.1"}})
	protector := &directTestProtector{allow: true}
	up, err := newDirectDNSUpstream(&Session{protector: protector}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	resolver, ok := up.resolvers[0].(*tlsResolver)
	if !ok {
		t.Fatal("Direct DoT must reuse the TLS resolver")
	}
	resolver.tlsCfg.RootCAs = roots
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := up.exchange(ctx, dnsQuery("direct.example")); err != nil {
		t.Fatal(err)
	}
	select {
	case name := <-server.serverNames:
		if name != "dns.test" {
			t.Fatalf("SNI=%q, want certificate hostname, not bootstrap IP", name)
		}
	default:
		t.Fatal("server received no TLS handshake")
	}
	if protector.calls.Load() != 1 {
		t.Fatalf("protected sockets=%d", protector.calls.Load())
	}
}

func TestDirectDNS_DoHBootstrapsAndPreservesAuthority(t *testing.T) {
	cert, roots := testCert(t)
	hosts := make(chan string, 8)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hosts <- r.Host
		if r.Method != http.MethodPost || r.URL.Path != "/dns-query" {
			http.Error(w, "unexpected request", http.StatusBadRequest)
			return
		}
		query, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
			return
		}
		w.Header().Set("Content-Type", dohContentType)
		_, _ = w.Write(dnsResponseFor(query, 60, [4]byte{192, 0, 2, 17}))
	}))
	server.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	_, port, _ := net.SplitHostPort(server.Listener.Addr().String())
	authority := "dns.test:" + port
	cfg := encryptedDirectConfig(t, "https", "https://"+authority+"/dns-query", map[string][]string{"dns.test": {"127.0.0.1"}})
	protector := &directTestProtector{allow: true}
	up, err := newDirectDNSUpstream(&Session{protector: protector}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	resolver, ok := up.resolvers[0].(*httpsResolver)
	if !ok {
		t.Fatal("Direct DoH must reuse the HTTPS resolver")
	}
	resolver.tlsCfg.RootCAs = roots
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	response, err := up.exchange(ctx, dnsQuery("direct.example"))
	if err != nil {
		t.Fatal(err)
	}
	if response[len(response)-1] != 17 {
		t.Fatalf("unexpected answer=%v", response)
	}
	if host := <-hosts; host != authority {
		t.Fatalf("HTTP authority=%q, want %q", host, authority)
	}
	if protector.calls.Load() != 1 {
		t.Fatalf("protected sockets=%d", protector.calls.Load())
	}
}

func TestDirectDNS_EncryptedConfigRequiresNumericBootstrap(t *testing.T) {
	for _, raw := range []string{
		`{"transport":"tls","servers":["dns.test:853"]}`,
		`{"transport":"https","servers":["https://dns.test/dns-query"]}`,
		`{"transport":"tls","servers":["dns.test:853"],"bootstrap":{"dns.test":["another.test"]}}`,
		`{"transport":"tls","servers":["dns.test:853"],"bootstrap":{"dns.test":[]}}`,
	} {
		if _, err := parseDirectDNSConfig(raw); err == nil {
			t.Errorf("must reject missing/non-numeric bootstrap without system DNS fallback: %s", raw)
		}
	}
}

func TestDirectDNS_DoTRejectsWrongCertificateWithoutPlaintextFallback(t *testing.T) {
	cert, roots := testCert(t)
	server := newRecordingDoTServer(t, cert)
	_, port, _ := net.SplitHostPort(server.addr)
	cfg := encryptedDirectConfig(t, "tls", "wrong.test:"+port, map[string][]string{"wrong.test": {"127.0.0.1"}})
	up, err := newDirectDNSUpstream(&Session{protector: &directTestProtector{allow: true}}, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer up.close()
	up.resolvers[0].(*tlsResolver).tlsCfg.RootCAs = roots
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := up.exchange(ctx, dnsQuery("direct.example")); err == nil {
		t.Fatal("certificate hostname mismatch must fail")
	}
}
