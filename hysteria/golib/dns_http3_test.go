package golib

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
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
	maxBody  atomic.Int32
	srv      *http3.Server

	mu     sync.Mutex
	bodies [][]byte
}

func (d *doh3Server) record(q []byte) {
	d.mu.Lock()
	d.bodies = append(d.bodies, append([]byte(nil), q...))
	d.mu.Unlock()
}

func (d *doh3Server) lastBody() []byte {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.bodies) == 0 {
		return nil
	}
	return d.bodies[len(d.bodies)-1]
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
			d.record(q)
			if rejectOversizeDoH(w, q, d.maxBody.Load()) {
				return
			}
			if err != nil || len(q) < 12 || r.Method != http.MethodPost {
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			if binary.BigEndian.Uint16(q[:2]) != 0 {
				t.Errorf("wire ID = %#x, want 0", binary.BigEndian.Uint16(q[:2]))
			}
			if rejectUnparsableDoH(w, q) {
				return
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

func TestH3Resolver_bodyIsTheQueryWithAZeroID(t *testing.T) {
	d := newDoH3Server(t, [4]byte{3, 3, 3, 3})
	fc, _ := d.client(t)
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	cases := []struct {
		name     string
		query    []byte
		rejected bool
	}{
		{"plain", dnsQuery("example.com"), false},
		{"EDNS with DO", withEDNS(dnsQuery("example.com"), 4096, true), false},
		{"padded to the limit", withPadding(dnsQuery("example.com"), dohFixtureQueryLimit), false},
		{"padded past the limit", withPadding(dnsQuery("example.com"), dohFixtureQueryLimit+1), false},
		{"300 bytes of non-DNS", nonDNSPayload(300), true},
	}
	for _, c := range cases {
		binary.BigEndian.PutUint16(c.query[:2], 0x3333)
		sent := append([]byte(nil), c.query...)
		_, err := r.exchange(ctx, c.query)
		if c.rejected && err == nil {
			t.Errorf("%s: the server's 400 must be an error", c.name)
		}
		if !c.rejected && err != nil {
			t.Fatalf("%s: exchange: %v", c.name, err)
		}
		want := append([]byte(nil), sent...)
		want[0], want[1] = 0, 0
		if got := d.lastBody(); !bytes.Equal(got, want) {
			t.Errorf("%s: body is %d bytes, want the %d-byte query with a zero ID", c.name, len(got), len(sent))
		}
		if !bytes.Equal(c.query, sent) {
			t.Errorf("%s: exchange modified the query it was given", c.name)
		}
	}
	if d.proto.Load() != 3 {
		t.Errorf("negotiated HTTP/%d, want HTTP/3", d.proto.Load())
	}
}

func TestH3Resolver_rejectionsNeitherTripTheGateNorFallBack(t *testing.T) {
	d := newDoH3Server(t, [4]byte{3, 3, 3, 3})
	d.maxBody.Store(dohFixtureQueryLimit)
	fc, _ := d.client(t)
	var tcpDials atomic.Int32
	fc.tcp = func(string) (net.Conn, error) {
		tcpDials.Add(1)
		return nil, errors.New("the HTTPS fallback must stay unused")
	}
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()

	rejected := []struct {
		query  []byte
		status int
	}{
		{withPadding(dnsQuery("example.com"), dohFixtureQueryLimit+1), http.StatusRequestEntityTooLarge},
		{nonDNSPayload(300), http.StatusBadRequest},
		{nonDNSPayload(1400), http.StatusRequestEntityTooLarge},
	}
	for i := 0; i <= fallbackGateThreshold; i++ {
		c := rejected[i%len(rejected)]
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_, err := r.exchange(ctx, c.query)
		cancel()
		var statusErr *dohStatusError
		if !errors.As(err, &statusErr) {
			t.Fatalf("exchange %d: err = %v, want a DoH status error", i, err)
		}
		if statusErr.status != c.status || statusErr.proto != "HTTP/3.0" || statusErr.queryLen != len(c.query) {
			t.Errorf("exchange %d: HTTP %d over %q for %d bytes, want %d over HTTP/3.0 for %d", i, statusErr.status, statusErr.proto, statusErr.queryLen, c.status, len(c.query))
		}
		if isTimeoutClass(err) {
			t.Fatalf("exchange %d: a rejection counted as a timeout", i)
		}
	}
	if r.gate.tripped() || r.isUDPDown() {
		t.Error("HTTP rejections must not move the resolver off HTTP/3")
	}
	if tcpDials.Load() != 0 {
		t.Errorf("HTTPS fallback dialed %d times", tcpDials.Load())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := r.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("a small query after the rejections: %v", err)
	}
	if resp[len(resp)-1] != 3 {
		t.Errorf("answer = %v", resp)
	}
	if got := d.requests.Load(); got != int32(fallbackGateThreshold+2) {
		t.Errorf("server saw %d requests, want %d", got, fallbackGateThreshold+2)
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

func TestH3Resolver_blackholeSwitchesToHTTPS(t *testing.T) {
	d := newDoHServer(t, [4]byte{4, 4, 4, 4}, http.StatusOK)
	fc := d.client()
	fc.udp = func() (client.HyUDPConn, error) { return newFakeUDPConn(nil), nil }
	r, err := newH3Resolver(fc, d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	defer r.close()
	r.rt.QUICConfig.HandshakeIdleTimeout = 200 * time.Millisecond

	for i := 0; i < fallbackGateThreshold; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_, err := r.exchange(ctx, dnsQuery("example.com"))
		cancel()
		if err == nil {
			t.Fatalf("exchange %d should fail against a blackholed relay", i)
		}
	}
	if r.isUDPDown() {
		t.Fatal("resolver must not switch before the fallback answers")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	resp, err := r.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange after the gate tripped: %v", err)
	}
	if resp[len(resp)-1] != 4 {
		t.Errorf("answer = %v", resp)
	}
	if !r.isUDPDown() {
		t.Error("resolver should stay on the HTTPS fallback")
	}
}
