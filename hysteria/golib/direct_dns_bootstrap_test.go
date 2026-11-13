package golib

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// These tests are deliberately not parallel: the fixture CA temporarily replaces
// the cached system trust store, exactly as installing a test CA would on device.
func TestDirectDNS_EncryptedTunnelHostnameBootstrap(t *testing.T) {
	cert, roots := testCert(t)
	previousRoots := dnsRootCAs()
	dnsRoots = roots
	defer func() { dnsRoots = previousRoots }()

	answer := func(query []byte) []byte {
		response := dnsResponseFor(query, 60, [4]byte{192, 0, 2, 17})
		response[10], response[11] = 0, 0
		if qtype, _ := dnsQuestionType(query); qtype == dnsTypeAAAA {
			response = response[:skipName(query, 12)+4]
			response[6], response[7] = 0, 0
		}
		return response
	}

	for _, transport := range []string{"tls", "https"} {
		t.Run(transport, func(t *testing.T) {
			var endpoint string
			if transport == "tls" {
				ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
				if err != nil {
					t.Fatal(err)
				}
				defer ln.Close()
				_, port, _ := net.SplitHostPort(ln.Addr().String())
				endpoint = "dns.test:" + port
				go func() {
					for {
						conn, err := ln.Accept()
						if err != nil {
							return
						}
						go func() {
							defer conn.Close()
							_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
							for {
								query, err := readDNSFrame(conn)
								if err != nil {
									return
								}
								if err := writeDNSFrame(conn, answer(query)); err != nil {
									return
								}
							}
						}()
					}
				}()
			} else {
				server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					query, err := io.ReadAll(r.Body)
					if err != nil {
						return
					}
					w.Header().Set("Content-Type", dohContentType)
					_, _ = w.Write(answer(query))
				}))
				server.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
				server.EnableHTTP2 = true
				server.StartTLS()
				defer server.Close()
				_, port, _ := net.SplitHostPort(server.Listener.Addr().String())
				endpoint = "https://dns.test:" + port + "/dns-query"
			}
			cfg := encryptedDirectConfig(t, transport, endpoint, map[string][]string{"dns.test": {"127.0.0.1"}})
			protector := &directTestProtector{allow: true}
			session := &Session{protector: protector, directDNS: cfg}
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			ips, err := session.lookupDirectHost(ctx, "tunnel-server.invalid")
			if err != nil {
				t.Fatalf("resolve tunnel hostname through Direct %s before client exists: %v", transport, err)
			}
			if len(ips) != 1 || ips[0].IP.String() != "192.0.2.17" {
				t.Fatalf("bootstrap addresses=%v", ips)
			}
			if protector.calls.Load() == 0 {
				t.Fatal("bootstrap bypassed socket protection")
			}
		})
	}
}
