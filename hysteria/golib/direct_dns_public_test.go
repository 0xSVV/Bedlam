package golib

import (
	"context"
	"encoding/binary"
	"os"
	"testing"
	"time"
)

// Opt-in verification against the real providers, outside the deterministic suite.
// BEDLAM_PUBLIC_DNS_TEST=1 go test -run TestPublicDNSPresets -v
func TestPublicDNSPresets(t *testing.T) {
	if os.Getenv("BEDLAM_PUBLIC_DNS_TEST") != "1" {
		t.Skip("set BEDLAM_PUBLIC_DNS_TEST=1 to query public DNS providers")
	}
	for _, tc := range []struct {
		name, transport, endpoint, host, address string
	}{
		{"OpenDNS/UDP", "udp", "208.67.222.222:53", "", ""},
		{"OpenDNS/TCP", "tcp", "208.67.222.222:53", "", ""},
		{"OpenDNS/HTTPS", "https", "https://doh.opendns.com/dns-query", "doh.opendns.com", "146.112.41.2"},
		{"AdGuard/UDP", "udp", "94.140.14.14:53", "", ""},
		{"AdGuard/TCP", "tcp", "94.140.14.14:53", "", ""},
		{"AdGuard/TLS", "tls", "dns.adguard-dns.com:853", "dns.adguard-dns.com", "94.140.14.14"},
		{"AdGuard/HTTPS", "https", "https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com", "94.140.14.14"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			bootstrap := map[string][]string{}
			if tc.host != "" {
				bootstrap[tc.host] = []string{tc.address}
			}
			cfg := encryptedDirectConfig(t, tc.transport, tc.endpoint, bootstrap)
			up, err := newDirectDNSUpstream(&Session{protector: &directTestProtector{allow: true}}, cfg)
			if err != nil {
				t.Fatal(err)
			}
			defer up.close()
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			defer cancel()
			reply, err := up.exchange(ctx, dnsQuery("example.com"))
			if err != nil {
				t.Fatal(err)
			}
			if len(reply) < 12 || reply[3]&15 != 0 || binary.BigEndian.Uint16(reply[6:8]) == 0 {
				t.Fatalf("DNS reply has no successful answer: %x", reply)
			}
			t.Logf("verified successful DNS answer over %s", tc.transport)
		})
	}
}
