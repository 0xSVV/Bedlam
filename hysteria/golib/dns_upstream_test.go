package golib

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
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
		{"tls", "one.one.one.one:853", "one.one.one.one:853", false},
		{"tls", "1.1.1.1", "1.1.1.1:853", false},
		{"tls", "1.1.1.1:8853", "1.1.1.1:8853", false},
		{"tls", "2001:4860:4860::8888", "[2001:4860:4860::8888]:853", false},
		{"quic", "dns.adguard-dns.com", "dns.adguard-dns.com:853", false},
		{"quic", "quic://dns.adguard-dns.com", "dns.adguard-dns.com:853", false},
		{"quic", "quic://dns.adguard-dns.com:8853", "dns.adguard-dns.com:8853", false},
		{"quic", "1.1.1.1", "1.1.1.1:853", false},
		{"quic", "quic://[2606:4700:4700::1111]:8853", "[2606:4700:4700::1111]:8853", false},
		{"quic", "quic://dns.adguard-dns.com:0", "", true},
		{"quic", "quic://", "", true},
		{"https", "1.1.1.1", "https://1.1.1.1/dns-query", false},
		{"https", "dns.google", "https://dns.google/dns-query", false},
		{"https", "2001:4860:4860::8888", "https://[2001:4860:4860::8888]/dns-query", false},
		{"https", "[2001:4860:4860::8888]:8443", "", true},
		{"https", "1.1.1.1:53", "", true},
		{"https", "https://dns.google/dns-query", "https://dns.google/dns-query", false},
		{"https", "https://cloudflare-dns.com/dns-query", "https://cloudflare-dns.com/dns-query", false},
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

func TestNewDNSUpstream_hostnamePresetsKeepTheHost(t *testing.T) {
	build := func(transport, servers string) *dnsUpstream {
		t.Helper()
		cfg, err := parseDNSUpstream(`{"transport":"` + transport + `","servers":[` + servers + `],"listen":["172.19.0.2","fdfe:dcba:9876::2"]}`)
		if err != nil {
			t.Fatal(err)
		}
		up, err := newDNSUpstream(&fakeClient{}, cfg)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(up.close)
		return up
	}

	dot := build("tls", `"one.one.one.one:853"`)
	if dot.id() != "tls|one.one.one.one:853" {
		t.Errorf("DoT id = %q", dot.id())
	}
	tlsR := dot.resolvers[0].(*tlsResolver)
	if tlsR.server != "one.one.one.one:853" || tlsR.tlsCfg.ServerName != "one.one.one.one" {
		t.Errorf("DoT server=%q sni=%q", tlsR.server, tlsR.tlsCfg.ServerName)
	}
	if tlsR.tlsCfg.InsecureSkipVerify || tlsR.tlsCfg.RootCAs == nil {
		t.Error("DoT to a hostname must verify the certificate against the system roots")
	}

	doh := build("https", `"https://cloudflare-dns.com/dns-query"`)
	if doh.id() != "https|https://cloudflare-dns.com/dns-query" {
		t.Errorf("DoH id = %q", doh.id())
	}
	httpsR := doh.resolvers[0].(*httpsResolver)
	if httpsR.url != "https://cloudflare-dns.com/dns-query" || httpsR.dial != "cloudflare-dns.com:443" || httpsR.tlsCfg.ServerName != "cloudflare-dns.com" {
		t.Errorf("DoH url=%q dial=%q sni=%q", httpsR.url, httpsR.dial, httpsR.tlsCfg.ServerName)
	}
	if httpsR.tlsCfg.InsecureSkipVerify || httpsR.tlsCfg.RootCAs == nil {
		t.Error("DoH to a hostname must verify the certificate against the system roots")
	}

	doh3 := build("http3", `"https://1.1.1.1/dns-query","https://[2606:4700:4700::1111]/dns-query"`)
	numeric := []struct {
		dial string
		name string
	}{
		{"1.1.1.1:443", "1.1.1.1"},
		{"[2606:4700:4700::1111]:443", "2606:4700:4700::1111"},
	}
	for i, want := range numeric {
		h3R := doh3.resolvers[i].(*h3Resolver)
		if h3R.dial != want.dial || h3R.tlsCfg.ServerName != want.name {
			t.Errorf("HTTP/3 %d dial=%q sni=%q, want %q and %q", i, h3R.dial, h3R.tlsCfg.ServerName, want.dial, want.name)
		}
		if h3R.fallback.dial != want.dial {
			t.Errorf("HTTP/3 %d fallback dial=%q, want %q", i, h3R.fallback.dial, want.dial)
		}
	}
}

func TestDNSUpstream_singleServerFailsWithinTheAttemptCap(t *testing.T) {
	var answering atomic.Bool
	lone := &stubResolver{name: "tls|one.one.one.one:853", reply: func(query []byte) ([]byte, error) {
		if answering.Load() {
			return echoAnswer([4]byte{1, 1, 1, 1})(query)
		}
		time.Sleep(30 * time.Second)
		return nil, errors.New("unreachable")
	}}
	up := &dnsUpstream{resolvers: []dnsResolver{lone}, ident: lone.name}

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	start := time.Now()
	_, err := up.exchange(ctx, dnsQuery("example.com"))
	elapsed := time.Since(start)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want the attempt deadline", err)
	}
	if elapsed > dnsAttemptTimeout+time.Second {
		t.Errorf("a lone blackholed server held the query for %v, want at most %v", elapsed, dnsAttemptTimeout)
	}
	if lone.calls.Load() != 1 {
		t.Errorf("resolver called %d times, want 1", lone.calls.Load())
	}

	answering.Store(true)
	next, cancelNext := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancelNext()
	if _, err := up.exchange(next, dnsQuery("example.com")); err != nil {
		t.Fatalf("the next query after a total failure: %v", err)
	}
	if lone.calls.Load() != 2 {
		t.Errorf("resolver called %d times, want the lone server tried again", lone.calls.Load())
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

func blockUntilCleanup(t *testing.T) <-chan struct{} {
	release := make(chan struct{})
	t.Cleanup(func() { close(release) })
	return release
}

func TestDNSUpstream_slowServerAnswersAfterItsSliceWhileTheNextIsPending(t *testing.T) {
	release := blockUntilCleanup(t)
	nextStarted := make(chan struct{})
	slow := &stubResolver{name: "slow", reply: func(q []byte) ([]byte, error) {
		<-nextStarted
		return echoAnswer([4]byte{1, 1, 1, 1})(q)
	}}
	pending := &stubResolver{name: "pending", reply: func([]byte) ([]byte, error) {
		close(nextStarted)
		<-release
		return nil, errors.New("released")
	}}
	up := &dnsUpstream{resolvers: []dnsResolver{slow, pending}, ident: "tls|slow,pending"}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("the first server answers after its slice while the next is pending, so the query must succeed: %v", err)
	}
	if resp[len(resp)-1] != 1 {
		t.Errorf("answer = %v, want the first server's", resp)
	}
	if pending.calls.Load() != 1 {
		t.Errorf("the next server was called %d times, want it started once the first had its slice", pending.calls.Load())
	}
}

func TestDNSUpstream_keepsServerOrderWhileStaggering(t *testing.T) {
	release := blockUntilCleanup(t)
	var mu sync.Mutex
	starts := map[string]time.Time{}
	began := func(name string) {
		mu.Lock()
		starts[name] = time.Now()
		mu.Unlock()
	}
	secondStarted := make(chan struct{})
	first := &stubResolver{name: "first", reply: func([]byte) ([]byte, error) {
		<-secondStarted
		return nil, errors.New("first refused")
	}}
	second := &stubResolver{name: "second", reply: func([]byte) ([]byte, error) {
		began("second")
		close(secondStarted)
		<-release
		return nil, errors.New("released")
	}}
	third := &stubResolver{name: "third", reply: func(q []byte) ([]byte, error) {
		began("third")
		return echoAnswer([4]byte{3, 3, 3, 3})(q)
	}}
	up := &dnsUpstream{resolvers: []dnsResolver{first, second, third}, ident: "tls|first,second,third"}

	ctx, cancel := context.WithTimeout(context.Background(), 3*dnsMinAttemptTimeout)
	defer cancel()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("the third server answers, so the query must succeed: %v", err)
	}
	if resp[len(resp)-1] != 3 {
		t.Errorf("answer = %v, want the third server's", resp)
	}
	mu.Lock()
	gap := starts["third"].Sub(starts["second"])
	mu.Unlock()
	if gap < dnsMinAttemptTimeout-100*time.Millisecond {
		t.Errorf("the third server started %v after the second, want the second given its whole slice although the first failed meanwhile", gap)
	}
}

func TestDNSUpstream_failedServerStartsTheNextAtOnce(t *testing.T) {
	failing := &stubResolver{name: "failing", reply: func([]byte) ([]byte, error) { return nil, errors.New("refused") }}
	working := &stubResolver{name: "working", reply: echoAnswer([4]byte{2, 2, 2, 2})}
	up := &dnsUpstream{resolvers: []dnsResolver{failing, working}, ident: "tls|failing,working"}

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	start := time.Now()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 2 {
		t.Errorf("answer = %v, want the second server's", resp)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("the second server answered after %v, want it started as soon as the first failed", elapsed)
	}
}

func TestDNSUpstream_lateAnswerFromAServerThatLostReachesTheHook(t *testing.T) {
	release := make(chan struct{})
	slow := &stubResolver{name: "slow", reply: func(q []byte) ([]byte, error) {
		<-release
		return echoAnswer([4]byte{1, 1, 1, 1})(q)
	}}
	fast := &stubResolver{name: "fast", reply: echoAnswer([4]byte{2, 2, 2, 2})}
	up := &dnsUpstream{resolvers: []dnsResolver{slow, fast}, ident: "tls|slow,fast"}

	base, late := collectLateAnswers(context.Background())
	ctx, cancel := context.WithTimeout(base, 3*time.Second)
	defer cancel()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 2 {
		t.Fatalf("answer = %v, want the second server's", resp)
	}
	close(release)
	select {
	case answer := <-late:
		if answer[len(answer)-1] != 1 {
			t.Errorf("late answer = %v, want the first server's", answer)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the first server answered after the query returned, but its answer never reached the late-answer hook")
	}
}

func TestDNSUpstream_failsOverPastASilentServer(t *testing.T) {
	silent := newFaultDNSServer(t, func(int, int) streamFault { return faultSilent })
	healthy := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	up := &dnsUpstream{
		resolvers: []dnsResolver{newTCPResolver(silent.client(), "1.1.1.1:53"), newTCPResolver(healthy.client(), "1.0.0.1:53")},
		ident:     "tcp|1.1.1.1:53,1.0.0.1:53",
	}
	defer up.close()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	start := time.Now()
	if _, err := up.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("the second server answers, so the query must succeed: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 2500*time.Millisecond {
		t.Errorf("failover took %v, want the silent server cut off at its share of the budget", elapsed)
	}
	if up.preferred.Load() != 1 {
		t.Errorf("preferred = %d, want the healthy server", up.preferred.Load())
	}
	if silent.queries.Load() != 1 {
		t.Errorf("silent server saw %d queries, want 1", silent.queries.Load())
	}
}

func TestDNSUpstream_allSilentServersFailInBudgetThenRecover(t *testing.T) {
	var down atomic.Bool
	down.Store(true)
	fault := func(int, int) streamFault {
		if down.Load() {
			return faultSilent
		}
		return faultAnswer
	}
	a, b := newFaultDNSServer(t, fault), newFaultDNSServer(t, fault)
	up := &dnsUpstream{
		resolvers: []dnsResolver{newTCPResolver(a.client(), "1.1.1.1:53"), newTCPResolver(b.client(), "1.0.0.1:53")},
		ident:     "tcp|1.1.1.1:53,1.0.0.1:53",
	}
	defer up.close()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	start := time.Now()
	_, err := up.exchange(ctx, dnsQuery("example.com"))
	cancel()
	if !isTimeoutClass(err) {
		t.Fatalf("err = %v, want a timeout", err)
	}
	if elapsed := time.Since(start); elapsed > 3500*time.Millisecond {
		t.Errorf("a total failure took %v, want it within the 3s query budget", elapsed)
	}
	if a.queries.Load() != 1 || b.queries.Load() != 1 {
		t.Errorf("servers saw %d and %d queries, want each tried once", a.queries.Load(), b.queries.Load())
	}

	down.Store(false)
	ctx, cancel = context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	start = time.Now()
	if _, err := up.exchange(ctx, dnsQuery("example.org")); err != nil {
		t.Fatalf("the next query once the servers answer: %v", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("the next query took %v, want it answered at once", elapsed)
	}
}

func TestDNSUpstream_singleServerSurvivesAStalePooledStream(t *testing.T) {
	var stale atomic.Bool
	srv := newFaultDNSServer(t, func(conn, _ int) streamFault {
		if stale.Load() && conn == 1 {
			return faultSilent
		}
		return faultAnswer
	})
	r := newTCPResolver(srv.client(), "1.1.1.1:53")
	up := &dnsUpstream{resolvers: []dnsResolver{r}, ident: "tcp|1.1.1.1:53"}
	defer up.close()
	fillPool(t, r.pool, 1)
	stale.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	start := time.Now()
	if _, err := up.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("a lone server whose new streams answer must survive a stale pooled one: %v", err)
	}
	if elapsed := time.Since(start); elapsed >= dnsAttemptTimeout {
		t.Errorf("query took %v, want it answered inside one %v attempt", elapsed, dnsAttemptTimeout)
	}
}

func TestDNSUpstream_slowResolverStillAnswersPastHalfTheAttempt(t *testing.T) {
	for _, tc := range []struct {
		name    string
		servers int
		pooled  int
		budget  time.Duration
		delay   time.Duration
	}{
		{"lone server", 1, 2, 3 * time.Second, 1800 * time.Millisecond},
		{"four servers", 4, 1, dnsQueryTimeout, 1400 * time.Millisecond},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var slow atomic.Bool
			var resolvers []*tcpResolver
			up := &dnsUpstream{ident: "tcp|slow"}
			defer up.close()
			for i := 0; i < tc.servers; i++ {
				dial := loopbackTCPDNSServer(t, func(_ int, q []byte) []byte {
					if slow.Load() {
						time.Sleep(tc.delay)
					}
					return dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
				})
				r := newTCPResolver(&fakeClient{tcp: func(string) (net.Conn, error) { return dial() }}, fmt.Sprintf("192.0.2.%d:53", i+1))
				fillPool(t, r.pool, tc.pooled)
				resolvers = append(resolvers, r)
				up.resolvers = append(up.resolvers, r)
			}
			slow.Store(true)

			ctx, cancel := context.WithTimeout(context.Background(), tc.budget)
			defer cancel()
			start := time.Now()
			if _, err := up.exchange(ctx, dnsQuery("slow.example")); err != nil {
				t.Fatalf("a resolver that answers every query in %v must still answer: %v", tc.delay, err)
			}
			if elapsed := time.Since(start); elapsed > tc.delay+time.Second {
				t.Errorf("query took %v, want the first server's pooled stream to answer after %v", elapsed, tc.delay)
			}
			if held := len(resolvers[0].pool.idle); held != tc.pooled {
				t.Errorf("first server's pool holds %d streams, want its %d slow but live streams kept", held, tc.pooled)
			}
		})
	}
}

func TestDNSUpstream_staleStreamsCostOneSlowQueryNotFour(t *testing.T) {
	var stale atomic.Bool
	srv := newFaultDNSServer(t, func(conn, _ int) streamFault {
		if stale.Load() && conn <= dnsPoolSize {
			return faultSilent
		}
		return faultAnswer
	})
	r := newTCPResolver(srv.client(), "1.1.1.1:53")
	up := &dnsUpstream{resolvers: []dnsResolver{r}, ident: "tcp|1.1.1.1:53"}
	defer up.close()
	fillPool(t, r.pool, dnsPoolSize)
	stale.Store(true)

	for i := 0; i < dnsPoolSize; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		start := time.Now()
		_, err := up.exchange(ctx, dnsQuery(fmt.Sprintf("q%d.example", i)))
		elapsed := time.Since(start)
		cancel()
		if err != nil {
			t.Fatalf("query %d: %v", i, err)
		}
		if i > 0 && elapsed > 300*time.Millisecond {
			t.Errorf("query %d took %v, want only the first query to wait on a stale stream", i, elapsed)
		}
	}
}
