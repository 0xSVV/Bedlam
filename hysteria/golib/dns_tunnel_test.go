package golib

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/hysteria/core/v2/server"
)

type blackholePacketConn struct {
	net.PacketConn
	drop *atomic.Bool
}

func (c *blackholePacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	if c.drop.Load() {
		return len(b), nil
	}
	return c.PacketConn.WriteTo(b, addr)
}

func (c *blackholePacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		n, addr, err := c.PacketConn.ReadFrom(b)
		if err != nil || !c.drop.Load() {
			return n, addr, err
		}
	}
}

type blackholeConnFactory struct{ drop *atomic.Bool }

func (f blackholeConnFactory) New(net.Addr) (net.PacketConn, error) {
	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		return nil, err
	}
	return &blackholePacketConn{PacketConn: conn, drop: f.drop}, nil
}

type acceptAllAuth struct{}

func (acceptAllAuth) Authenticate(net.Addr, string, uint64) (bool, string) { return true, "test" }

type tcpOnlyOutbound func(addr string) (net.Conn, error)

func (o tcpOnlyOutbound) TCP(addr string) (net.Conn, error) { return o(addr) }

func (tcpOnlyOutbound) UDP(string) (server.UDPConn, error) { return nil, errors.New("udp disabled") }

func (tcpOnlyOutbound) CheckUDP(string) error { return errors.New("udp disabled") }

type testTunnel struct {
	*reconnectClient
	mu   sync.Mutex
	drop *atomic.Bool
}

func newTestTunnel(t *testing.T, fastOpen bool, outbound func(addr string) (net.Conn, error)) *testTunnel {
	t.Helper()
	cert, _ := testCert(t)
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv, err := server.NewServer(&server.Config{
		TLSConfig:     server.TLSConfig{Certificates: []tls.Certificate{cert}},
		Conn:          pc,
		Outbound:      tcpOnlyOutbound(outbound),
		Authenticator: acceptAllAuth{},
	})
	if err != nil {
		t.Fatal(err)
	}
	go func() { _ = srv.Serve() }()
	t.Cleanup(func() { _ = srv.Close() })

	tt := &testTunnel{}
	rc, err := newReconnectClient(func() (*client.Config, error) {
		drop := &atomic.Bool{}
		tt.mu.Lock()
		tt.drop = drop
		tt.mu.Unlock()
		return &client.Config{
			ServerAddr:  pc.LocalAddr(),
			TLSConfig:   client.TLSConfig{InsecureSkipVerify: true},
			FastOpen:    fastOpen,
			ConnFactory: blackholeConnFactory{drop: drop},
		}, nil
	}, &recordingHandler{}, nil, false, false)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = rc.Close() })
	tt.reconnectClient = rc
	return tt
}

func (tt *testTunnel) blackhole() {
	tt.mu.Lock()
	defer tt.mu.Unlock()
	tt.drop.Store(true)
}

func TestTCPResolver_recoversAfterTheTunnelReconnects(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	tt := newTestTunnel(t, true, srv.connect)
	r := newTCPResolver(tt, "8.8.8.8:53")
	defer r.close()
	fillPool(t, r.pool, dnsPoolSize)

	tt.markDead(errors.New("test reconnect"), srcWatchdog)
	var wg sync.WaitGroup
	for i := 0; i < dnsPoolSize; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			start := time.Now()
			if _, err := r.exchange(ctx, dnsQuery(fmt.Sprintf("q%d.example", i))); err != nil {
				t.Errorf("query %d after the reconnect: %v", i, err)
			}
			if elapsed := time.Since(start); elapsed > 2*time.Second {
				t.Errorf("query %d after the reconnect took %v", i, elapsed)
			}
		}(i)
	}
	wg.Wait()
	if seq := tt.SessionSeq(); seq != 2 {
		t.Errorf("session %d answered, want the replacement session 2", seq)
	}
	if conns := srv.conns.Load(); conns <= dnsPoolSize {
		t.Errorf("resolver saw %d connections, want the stale streams replaced by new ones", conns)
	}
}

func TestTLSResolver_recoversAfterTheTunnelReconnects(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte { return echoDoT([4]byte{1, 1, 1, 1})(q) })
	tt := newTestTunnel(t, true, func(string) (net.Conn, error) { return dial() })
	r := newTLSResolver(tt, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()
	fillPool(t, r.pool, dnsPoolSize)

	tt.markDead(errors.New("test reconnect"), srcWatchdog)
	for i := 0; i < dnsPoolSize+1; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		start := time.Now()
		_, err := r.exchange(ctx, dnsQuery(fmt.Sprintf("q%d.example", i)))
		cancel()
		if err != nil {
			t.Fatalf("query %d after the reconnect: %v", i, err)
		}
		if elapsed := time.Since(start); elapsed > 2*time.Second {
			t.Errorf("query %d after the reconnect took %v", i, elapsed)
		}
	}
	if seq := tt.SessionSeq(); seq != 2 {
		t.Errorf("session %d answered, want the replacement session 2", seq)
	}
}

func TestTCPResolver_pooledQueryRidesOutASilentTunnelBeingReplaced(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	tt := newTestTunnel(t, true, srv.connect)
	r := newTCPResolver(tt, "8.8.8.8:53")
	defer r.close()
	fillPool(t, r.pool, 2)
	tt.blackhole()

	ctx, cancel := context.WithTimeout(context.Background(), 800*time.Millisecond)
	start := time.Now()
	_, err := r.exchange(ctx, dnsQuery("dead.example"))
	cancel()
	if !isTimeoutClass(err) {
		t.Fatalf("silent tunnel: err = %v, want a timeout", err)
	}
	if elapsed := time.Since(start); elapsed > 1500*time.Millisecond {
		t.Errorf("silent tunnel held the query for %v, want it bounded by its 800ms budget", elapsed)
	}

	if held := len(r.pool.idle); held != 1 {
		t.Fatalf("pool holds %d streams, want the one the failed query left untouched", held)
	}
	time.AfterFunc(200*time.Millisecond, func() { tt.markDead(errors.New("idle probe failed"), srcWatchdog) })
	ctx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start = time.Now()
	if _, err := r.exchange(ctx, dnsQuery("inflight.example")); err != nil {
		t.Fatalf("query waiting on a pooled stream across the replacement: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Errorf("query waiting on a pooled stream took %v, want release when the tunnel is replaced", elapsed)
	}
	for i := 0; i < 3; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_, err := r.exchange(ctx, dnsQuery(fmt.Sprintf("after%d.example", i)))
		cancel()
		if err != nil {
			t.Fatalf("query %d after the replacement: %v", i, err)
		}
	}
	if seq := tt.SessionSeq(); seq != 2 {
		t.Errorf("session %d answered, want the replacement session 2", seq)
	}
}

func TestDNSUpstream_loneServerRidesOutATunnelReplacedLateInTheAttempt(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	tt := newTestTunnel(t, true, srv.connect)
	r := newTCPResolver(tt, "8.8.8.8:53")
	up := &dnsUpstream{resolvers: []dnsResolver{r}, ident: "tcp|8.8.8.8:53"}
	defer up.close()
	fillPool(t, r.pool, 1)
	tt.blackhole()

	const budget = 2 * time.Second
	replace := time.AfterFunc(budget*3/4, func() { tt.markDead(errors.New("idle probe failed"), srcWatchdog) })
	defer replace.Stop()
	ctx, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()
	start := time.Now()
	if _, err := up.exchange(ctx, dnsQuery("inflight.example")); err != nil {
		t.Fatalf("a lone server's pooled query must be answered when the tunnel is replaced late in its attempt: %v", err)
	}
	if elapsed := time.Since(start); elapsed >= budget {
		t.Errorf("query took %v, want it answered on the new session inside its %v budget", elapsed, budget)
	}
	if seq := tt.SessionSeq(); seq != 2 {
		t.Errorf("session %d answered, want the replacement session 2", seq)
	}
}

func TestTCPResolver_retriesWhenTheResolverClosesAnIdleStreamThroughTheTunnel(t *testing.T) {
	srv := newFaultDNSServer(t, func(conn, _ int) streamFault {
		if conn == 1 {
			return faultAnswerThenClose
		}
		return faultAnswer
	})
	tt := newTestTunnel(t, true, srv.connect)
	r := newTCPResolver(tt, "8.8.8.8:53")
	defer r.close()
	fillPool(t, r.pool, 1)
	time.Sleep(200 * time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	start := time.Now()
	resp, err := r.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("query after the resolver closed the idle stream: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Errorf("query after the idle close took %v, want the closed stream noticed at once", elapsed)
	}
	if conn := answerConn(resp); conn != 2 {
		t.Errorf("answer came from connection %d, want a new connection 2", conn)
	}
}

func hangingOutbound(t *testing.T) func(string) (net.Conn, error) {
	release := make(chan struct{})
	t.Cleanup(func() { close(release) })
	return func(string) (net.Conn, error) {
		<-release
		return nil, errors.New("released")
	}
}

func TestTCPResolver_fastOpenReportsAHungServerDialAsAResponseTimeout(t *testing.T) {
	for _, tc := range []struct {
		fastOpen bool
		want     string
	}{
		{true, "read response length"},
		{false, "dial DNS server 8.8.8.8:53"},
	} {
		tt := newTestTunnel(t, tc.fastOpen, hangingOutbound(t))
		r := newTCPResolver(tt, "8.8.8.8:53")
		ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
		start := time.Now()
		_, err := r.exchange(ctx, dnsQuery("example.com"))
		elapsed := time.Since(start)
		cancel()
		r.close()
		if !isTimeoutClass(err) || !strings.Contains(fmt.Sprint(err), tc.want) {
			t.Errorf("fastOpen=%v: err = %v, want a timeout mentioning %q", tc.fastOpen, err, tc.want)
		}
		if elapsed > time.Second {
			t.Errorf("fastOpen=%v: a hung server dial held the query for %v", tc.fastOpen, elapsed)
		}
	}
}

func TestTLSResolver_fastOpenReportsAHungServerDialAsAHandshakeTimeout(t *testing.T) {
	_, pool := testCert(t)
	tt := newTestTunnel(t, true, hangingOutbound(t))
	r := newTLSResolver(tt, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := r.exchange(ctx, dnsQuery("example.com"))
	if !isTimeoutClass(err) || !strings.Contains(fmt.Sprint(err), "DoT handshake with dns.test:853") {
		t.Errorf("err = %v, want a handshake timeout", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("a hung server dial held the DoT handshake for %v", elapsed)
	}
}

func TestTCPResolver_fastOpenReportsARefusedServerDialAsADialError(t *testing.T) {
	tt := newTestTunnel(t, true, func(string) (net.Conn, error) {
		return nil, errors.New("connect: connection refused")
	})
	r := newTCPResolver(tt, "8.8.8.8:53")
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	start := time.Now()
	_, err := r.exchange(ctx, dnsQuery("example.com"))
	var dialErr coreErrs.DialError
	if !errors.As(err, &dialErr) || !strings.Contains(fmt.Sprint(err), "read response length") {
		t.Errorf("err = %v, want a DialError carried by the first read", err)
	}
	if isTimeoutClass(err) {
		t.Errorf("err = %v, a refused dial must not look like a timeout", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("a refused server dial took %v to surface", elapsed)
	}
}

func TestWatchdogProbe_keepsTheTunnelWhenTheServerRefusesTheProbe(t *testing.T) {
	var probed atomic.Int32
	tt := newTestTunnel(t, true, func(addr string) (net.Conn, error) {
		if addr == probeDNSServer {
			probed.Add(1)
		}
		return nil, errors.New("connect: connection refused")
	})
	c, err := tt.currentClient(srcWatchdog)
	if err != nil {
		t.Fatal(err)
	}

	tt.probe(c, "Idle")
	if probed.Load() != 1 {
		t.Fatalf("server saw %d probe dials, want 1", probed.Load())
	}
	if now, err := tt.currentClient(srcWatchdog); err != nil || now != c {
		t.Errorf("a refused probe replaced the tunnel: client %p, err %v", now, err)
	}
	if seq := tt.SessionSeq(); seq != 1 {
		t.Errorf("session %d, want the original session 1 kept", seq)
	}
}
