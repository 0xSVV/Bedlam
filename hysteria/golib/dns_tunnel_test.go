package golib

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
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
	}, &recordingHandler{}, nil, false)
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
