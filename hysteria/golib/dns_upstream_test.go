package golib

import (
	"context"
	"errors"
	"net/netip"
	"testing"
	"time"
)

func TestParseDNSUpstream_json(t *testing.T) {
	cfg, err := parseDNSUpstream(`{"transport":"https","servers":["https://1.1.1.1/dns-query"],"listen":["172.19.0.2","fdfe:dcba:9876::2"]}`)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if cfg.Transport != "https" || len(cfg.Servers) != 1 || len(cfg.Listen) != 2 {
		t.Errorf("unexpected config: %+v", cfg)
	}
	if _, err := parseDNSUpstream(""); err == nil {
		t.Error("empty config should fail")
	}
	if _, err := parseDNSUpstream("{"); err == nil {
		t.Error("broken JSON should fail")
	}
}

func TestNormalizeDNSServer(t *testing.T) {
	cases := []struct {
		transport string
		raw       string
		want      string
		wantErr   bool
	}{
		{"udp", "1.1.1.1", "1.1.1.1:53", false},
		{"tcp", "1.1.1.1:5353", "1.1.1.1:5353", false},
		{"tcp", "  1.1.1.1  ", "1.1.1.1:53", false},
		{"udp", "2606:4700:4700::1111", "[2606:4700:4700::1111]:53", false},
		{"udp", "[2606:4700:4700::1111]", "[2606:4700:4700::1111]:53", false},
		{"udp", "[2606:4700:4700::1111]:5353", "[2606:4700:4700::1111]:5353", false},
		{"tcp", "dns.example", "", true},
		{"udp", "dns.example:53", "", true},
		{"tls", "dns.google", "dns.google:853", false},
		{"tls", "1.1.1.1", "1.1.1.1:853", false},
		{"tls", "1.1.1.1:8853", "1.1.1.1:8853", false},
		{"tls", "2001:4860:4860::8888", "[2001:4860:4860::8888]:853", false},
		{"quic", "dns.adguard-dns.com", "dns.adguard-dns.com:853", false},
		{"quic", "quic://dns.adguard-dns.com", "dns.adguard-dns.com:853", false},
		{"quic", "quic://dns.adguard-dns.com:8853", "dns.adguard-dns.com:8853", false},
		{"quic", "1.1.1.1", "1.1.1.1:853", false},
		{"quic", "quic://[2606:4700:4700::1111]:8853", "[2606:4700:4700::1111]:8853", false},
		{"quic", "quic://dns.adguard-dns.com:0", "", true},
		{"https", "1.1.1.1", "https://1.1.1.1/dns-query", false},
		{"https", "dns.google", "https://dns.google/dns-query", false},
		{"https", "2001:4860:4860::8888", "https://[2001:4860:4860::8888]/dns-query", false},
		{"https", "[2001:4860:4860::8888]:8443", "", true},
		{"https", "1.1.1.1:53", "", true},
		{"https", "https://dns.google/dns-query", "https://dns.google/dns-query", false},
		{"https", "https://dns.google", "https://dns.google/dns-query", false},
		{"https", "https://dns.google/", "https://dns.google/dns-query", false},
		{"https", "https://[2001:4860:4860::8888]/", "https://[2001:4860:4860::8888]/dns-query", false},
		{"https", "https://dns.google:8443/dns-query", "https://dns.google:8443/dns-query", false},
		{"https", "https://dns.google:8443/custom", "https://dns.google:8443/custom", false},
		{"http3", "https://dns.google/dns-query#frag", "https://dns.google/dns-query", false},
		{"https", "http://dns.google/dns-query", "", true},
		{"https", "https://user@dns.google/dns-query", "", true},
		{"https", "https:///dns-query", "", true},
		{"https", "https://dns.google:99999/", "", true},
		{"udp", "", "", true},
		{"udp", "1.1.1.1:0", "", true},
		{"udp", "1.1.1.1:65536", "", true},
		{"udp", "1.1.1.1:abc", "", true},
		{"udp", "[2606:4700::1111", "", true},
		{"udp", "[2606:4700::1111]x", "", true},
		{"udp", ":53", "", true},
		{"ftp", "1.1.1.1", "", true},
	}
	for _, c := range cases {
		got, err := normalizeDNSServer(c.transport, c.raw)
		if c.wantErr {
			if err == nil {
				t.Errorf("normalizeDNSServer(%q, %q) = %q, want error", c.transport, c.raw, got)
			}
			continue
		}
		if err != nil {
			t.Errorf("normalizeDNSServer(%q, %q): %v", c.transport, c.raw, err)
			continue
		}
		if got != c.want {
			t.Errorf("normalizeDNSServer(%q, %q) = %q, want %q", c.transport, c.raw, got, c.want)
		}
	}
}

func TestDohDialAddr(t *testing.T) {
	host, dial, err := dohDialAddr("https://[2001:4860:4860::8888]/dns-query")
	if err != nil {
		t.Fatal(err)
	}
	if host != "2001:4860:4860::8888" || dial != "[2001:4860:4860::8888]:443" {
		t.Errorf("got host=%q dial=%q", host, dial)
	}
	_, dial, _ = dohDialAddr("https://dns.google:8443/dns-query")
	if dial != "dns.google:8443" {
		t.Errorf("dial = %q", dial)
	}
}

func TestNewDNSUpstream_buildsAndIdentifies(t *testing.T) {
	up, err := newDNSUpstream(&fakeClient{}, &dnsUpstreamConfig{
		Transport: "tcp",
		Servers:   []string{"1.1.1.1", "[2606:4700:4700::1111]:53"},
		Listen:    []string{"172.19.0.2", "fdfe:dcba:9876::2"},
	})
	if err != nil {
		t.Fatalf("newDNSUpstream: %v", err)
	}
	defer up.close()
	if up.id() != "tcp|1.1.1.1:53,[2606:4700:4700::1111]:53" {
		t.Errorf("id = %q", up.id())
	}
	if !up.isListenAddr(netip.MustParseAddr("172.19.0.2")) ||
		!up.isListenAddr(netip.MustParseAddr("fdfe:dcba:9876::2")) ||
		!up.isListenAddr(netip.MustParseAddr("::ffff:172.19.0.2")) {
		t.Error("listen addresses not recognised")
	}
	if up.isListenAddr(netip.MustParseAddr("172.19.0.1")) {
		t.Error("interface address must not be a listen address")
	}
}

func TestNewDNSUpstream_rejectsBadConfig(t *testing.T) {
	cases := []dnsUpstreamConfig{
		{Transport: "tcp"},
		{Transport: "ftp", Servers: []string{"1.1.1.1"}},
		{Transport: "tcp", Servers: []string{"1.1.1.1:0"}},
		{Transport: "tcp", Servers: []string{"1.1.1.1"}, Listen: []string{"not-an-ip"}},
	}
	for _, c := range cases {
		if _, err := newDNSUpstream(&fakeClient{}, &c); err == nil {
			t.Errorf("config %+v should be rejected", c)
		}
	}
}

func TestDNSUpstream_failoverToNext(t *testing.T) {
	failing := &stubResolver{name: "a", reply: func([]byte) ([]byte, error) { return nil, errors.New("boom") }}
	working := &stubResolver{name: "b", reply: echoAnswer([4]byte{1, 1, 1, 1})}
	up := &dnsUpstream{resolvers: []dnsResolver{failing, working}, ident: "tcp|a,b"}

	resp, err := up.exchange(context.Background(), dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 1 {
		t.Errorf("answer = %v", resp)
	}
	if failing.calls.Load() != 1 || working.calls.Load() != 1 {
		t.Errorf("calls a=%d b=%d", failing.calls.Load(), working.calls.Load())
	}
	if up.preferred.Load() != 1 {
		t.Errorf("preferred = %d, want 1", up.preferred.Load())
	}

	if _, err := up.exchange(context.Background(), dnsQuery("example.org")); err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if failing.calls.Load() != 1 {
		t.Errorf("failed resolver retried while another one works: calls=%d", failing.calls.Load())
	}
	if working.calls.Load() != 2 {
		t.Errorf("preferred resolver calls = %d, want 2", working.calls.Load())
	}
}

func TestDNSUpstream_allFail(t *testing.T) {
	a := &stubResolver{name: "a", reply: func([]byte) ([]byte, error) { return nil, errors.New("a down") }}
	b := &stubResolver{name: "b", reply: func([]byte) ([]byte, error) { return nil, errors.New("b down") }}
	up := &dnsUpstream{resolvers: []dnsResolver{a, b}, ident: "tcp|a,b"}

	_, err := up.exchange(context.Background(), dnsQuery("example.com"))
	if err == nil || err.Error() != "b down" {
		t.Fatalf("err = %v, want last error", err)
	}
}

func TestDNSUpstream_reachesEveryServerWithinTheQueryBudget(t *testing.T) {
	blackhole := func(name string) *stubResolver {
		return &stubResolver{name: name, reply: func(q []byte) ([]byte, error) {
			// Never answers; only the attempt budget ends this.
			time.Sleep(30 * time.Second)
			return nil, errors.New("unreachable")
		}}
	}
	a, b, c := blackhole("a"), blackhole("b"), blackhole("c")
	d := &stubResolver{name: "d", reply: echoAnswer([4]byte{4, 4, 4, 4})}
	up := &dnsUpstream{resolvers: []dnsResolver{a, b, c, d}, ident: "tcp|a,b,c,d"}

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("the fourth server answers, so the query must succeed: %v", err)
	}
	if resp[len(resp)-1] != 4 {
		t.Errorf("answer = %v", resp)
	}
	for _, r := range []*stubResolver{a, b, c, d} {
		if r.calls.Load() != 1 {
			t.Errorf("resolver %s called %d times, want 1", r.name, r.calls.Load())
		}
	}
	if up.preferred.Load() != 3 {
		t.Errorf("preferred = %d, want 3", up.preferred.Load())
	}
}

func TestDNSUpstream_rotatesAfterTotalFailure(t *testing.T) {
	a := &stubResolver{name: "a", reply: func([]byte) ([]byte, error) { return nil, errors.New("a down") }}
	b := &stubResolver{name: "b", reply: func([]byte) ([]byte, error) { return nil, errors.New("b down") }}
	up := &dnsUpstream{resolvers: []dnsResolver{a, b}, ident: "tcp|a,b"}

	if _, err := up.exchange(context.Background(), dnsQuery("example.com")); err == nil {
		t.Fatal("expected failure")
	}
	if up.preferred.Load() != 1 {
		t.Errorf("preferred = %d, want 1 so the next query starts elsewhere", up.preferred.Load())
	}
}

func TestDNSUpstream_attemptBudget(t *testing.T) {
	up := &dnsUpstream{}
	if got := up.attemptBudget(context.Background(), 4); got != dnsAttemptTimeout {
		t.Errorf("no deadline: %v, want %v", got, dnsAttemptTimeout)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if got := up.attemptBudget(ctx, 4); got > dnsAttemptTimeout || got < 2*time.Second {
		t.Errorf("4 servers left of 10s: %v, want ~2.5s", got)
	}
	if got := up.attemptBudget(ctx, 1); got != dnsAttemptTimeout {
		t.Errorf("1 server left of 10s: %v, want the cap %v", got, dnsAttemptTimeout)
	}
	tight, cancelTight := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancelTight()
	if got := up.attemptBudget(tight, 4); got != dnsMinAttemptTimeout {
		t.Errorf("tight budget: %v, want the floor %v", got, dnsMinAttemptTimeout)
	}
}

func TestDNSUpstream_stopsWhenContextDone(t *testing.T) {
	a := &stubResolver{name: "a", reply: func([]byte) ([]byte, error) { return nil, errors.New("a down") }}
	b := &stubResolver{name: "b", reply: echoAnswer([4]byte{1, 1, 1, 1})}
	up := &dnsUpstream{resolvers: []dnsResolver{a, b}, ident: "tcp|a,b"}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if _, err := up.exchange(ctx, dnsQuery("example.com")); err == nil {
		t.Fatal("expected error with cancelled context")
	}
	if b.calls.Load() != 0 {
		t.Errorf("resolver b called after cancellation")
	}
}
