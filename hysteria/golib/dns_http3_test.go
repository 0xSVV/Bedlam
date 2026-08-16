package golib

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/quic-go/http3"
)

// bridgeUDPConn is a HyUDPConn backed by a real UDP socket connected to a
// loopback server; Send ignores the address like the Hysteria server would
// resolve it, and Receive reads datagrams straight from the socket.
type bridgeUDPConn struct {
	c         *net.UDPConn
	closeOnce sync.Once
}

func (b *bridgeUDPConn) Send(data []byte, _ string) error {
	_, err := b.c.Write(data)
	return err
}

func (b *bridgeUDPConn) Receive() ([]byte, string, error) {
	buf := make([]byte, 65535)
	n, err := b.c.Read(buf)
	if err != nil {
		return nil, "", err
	}
	return buf[:n], b.c.RemoteAddr().String(), nil
}

func (b *bridgeUDPConn) Close() error {
	b.closeOnce.Do(func() { _ = b.c.Close() })
	return nil
}

type doh3Server struct {
	addr     *net.UDPAddr
	pool     *x509.CertPool
	requests atomic.Int32
	proto    atomic.Int32
	srv      *http3.Server
}

func newDoH3Server(t *testing.T, ip [4]byte) *doh3Server {
	t.Helper()
	cert, pool := testCert(t)
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	d := &doh3Server{addr: udp.LocalAddr().(*net.UDPAddr), pool: pool}
	d.srv = &http3.Server{
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}},
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			d.requests.Add(1)
			d.proto.Store(int32(r.ProtoMajor))
			q, err := io.ReadAll(r.Body)
			if err != nil || len(q) < 12 || r.Method != http.MethodPost {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			if binary.BigEndian.Uint16(q[:2]) != 0 {
				t.Errorf("wire ID = %#x, want 0", binary.BigEndian.Uint16(q[:2]))
			}
			resp := dnsResponse("example.com", 60, ip)
			resp[0], resp[1] = 0, 0
			w.Header().Set("Content-Type", dohContentType)
			_, _ = w.Write(resp)
		}),
	}
	go func() { _ = d.srv.Serve(udp) }()
	t.Cleanup(func() {
		_ = d.srv.Close()
		_ = udp.Close()
	})
	return d
}

func (d *doh3Server) url() string { return "https://" + d.addr.String() + "/dns-query" }

// client returns a fake Hysteria client whose UDP sessions are bridged to the
// server; every opened bridge is recorded so tests can kill it.
func (d *doh3Server) client(t *testing.T) (*fakeClient, func() []*bridgeUDPConn) {
	t.Helper()
	var mu sync.Mutex
	var bridges []*bridgeUDPConn
	fc := &fakeClient{udp: func() (client.HyUDPConn, error) {
		c, err := net.DialUDP("udp", nil, d.addr)
		if err != nil {
			return nil, err
		}
		b := &bridgeUDPConn{c: c}
		mu.Lock()
		bridges = append(bridges, b)
		mu.Unlock()
		return b, nil
	}}
	return fc, func() []*bridgeUDPConn {
		mu.Lock()
		defer mu.Unlock()
		return append([]*bridgeUDPConn(nil), bridges...)
	}
}

func TestH3Resolver_roundTrip(t *testing.T) {
	d := newDoH3Server(t, [4]byte{3, 3, 3, 3})
	fc, _ := d.client(t)
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	q := dnsQuery("example.com")
	binary.BigEndian.PutUint16(q[:2], 0x3333)
	resp, err := r.exchange(ctx, q)
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0x3333 {
		t.Errorf("txid = %#x", binary.BigEndian.Uint16(resp[:2]))
	}
	if resp[len(resp)-1] != 3 {
		t.Errorf("answer = %v", resp)
	}
	if d.proto.Load() != 3 {
		t.Errorf("negotiated HTTP/%d, want HTTP/3", d.proto.Load())
	}
	if r.id() != "http3|"+d.url() {
		t.Errorf("id = %q", r.id())
	}

	if _, err := r.exchange(ctx, dnsQuery("example.org")); err != nil {
		t.Fatalf("second exchange: %v", err)
	}
	if d.requests.Load() != 2 {
		t.Errorf("server saw %d requests, want 2", d.requests.Load())
	}
}

func TestH3Resolver_redialsAfterSessionDies(t *testing.T) {
	d := newDoH3Server(t, [4]byte{3, 3, 3, 3})
	fc, bridges := d.client(t)
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}
	if len(bridges()) != 1 {
		t.Fatalf("opened %d sessions, want 1", len(bridges()))
	}
	bridges()[0].Close()
	time.Sleep(100 * time.Millisecond)

	if _, err := r.exchange(ctx, dnsQuery("example.org")); err != nil {
		t.Fatalf("exchange after the session died: %v", err)
	}
	if len(bridges()) != 2 {
		t.Errorf("opened %d sessions, want 2 (redial)", len(bridges()))
	}
}

func TestH3Resolver_redialKeepsTheDrainingConnection(t *testing.T) {
	d := newDoH3Server(t, [4]byte{3, 3, 3, 3})
	fc, _ := d.client(t)
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}

	r.mu.Lock()
	first := r.conns[0]
	r.mu.Unlock()

	// Force a fresh dial while the first connection is still usable.
	if _, err := r.dialQUIC(ctx, d.addr.String(), r.tlsCfg.Clone(), r.rt.QUICConfig); err != nil {
		t.Fatalf("redial: %v", err)
	}
	if first.pkt.isClosed() {
		t.Error("a live connection must be left to drain, not closed on redial")
	}
	r.mu.Lock()
	kept := len(r.conns)
	r.mu.Unlock()
	if kept != 2 {
		t.Errorf("kept %d connections, want 2", kept)
	}
}

func TestH3Resolver_prunesDeadAndExcessConnections(t *testing.T) {
	d := newDoH3Server(t, [4]byte{3, 3, 3, 3})
	fc, _ := d.client(t)
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	for i := 0; i < maxH3Conns+3; i++ {
		if _, err := r.dialQUIC(ctx, d.addr.String(), r.tlsCfg.Clone(), r.rt.QUICConfig); err != nil {
			t.Fatalf("dial %d: %v", i, err)
		}
	}
	r.mu.Lock()
	kept := len(r.conns)
	r.mu.Unlock()
	if kept > maxH3Conns {
		t.Errorf("kept %d connections, want at most %d", kept, maxH3Conns)
	}
}

func TestH3Resolver_udpDisabledFallsBackToHTTPS(t *testing.T) {
	d := newDoHServer(t, [4]byte{4, 4, 4, 4}, http.StatusOK)
	fc := d.client()
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for i := 0; i < 2; i++ {
		resp, err := r.exchange(ctx, dnsQuery("example.com"))
		if err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
		if resp[len(resp)-1] != 4 {
			t.Errorf("answer = %v", resp)
		}
	}
	if !r.isUDPDown() {
		t.Error("resolver should remember that UDP is unavailable")
	}
	if d.requests.Load() != 2 {
		t.Errorf("HTTPS fallback saw %d requests, want 2", d.requests.Load())
	}
}
