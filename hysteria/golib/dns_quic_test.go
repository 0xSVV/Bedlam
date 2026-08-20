package golib

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/quic-go"
)

type doqServer struct {
	addr     *net.UDPAddr
	pool     *x509.CertPool
	requests atomic.Int32
	wireID   atomic.Int32
	ln       *quic.Listener
}

func newDoQServer(t *testing.T, ip [4]byte) *doqServer {
	t.Helper()
	cert, pool := testCert(t)
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	d := &doqServer{addr: udp.LocalAddr().(*net.UDPAddr), pool: pool}
	tr := &quic.Transport{Conn: udp}
	d.ln, err = tr.Listen(
		&tls.Config{Certificates: []tls.Certificate{cert}, NextProtos: []string{doqALPN}},
		&quic.Config{MaxIdleTimeout: 30 * time.Second},
	)
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			conn, err := d.ln.Accept(context.Background())
			if err != nil {
				return
			}
			go func(conn *quic.Conn) {
				for {
					st, err := conn.AcceptStream(context.Background())
					if err != nil {
						return
					}
					go func() {
						q, err := readDNSFrame(st)
						if err != nil {
							return
						}
						d.wireID.Store(int32(binary.BigEndian.Uint16(q[:2])))
						d.requests.Add(1)
						_ = writeDNSFrame(st, dnsResponseFor(q, 60, ip))
						_ = st.Close()
					}()
				}
			}(conn)
		}
	}()
	t.Cleanup(func() {
		_ = d.ln.Close()
		_ = tr.Close()
		_ = udp.Close()
	})
	return d
}

func (d *doqServer) server() string { return d.addr.String() }

func (d *doqServer) client(t *testing.T) (*fakeClient, func() []*bridgeUDPConn) {
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

func TestDoQResolver_roundTrip(t *testing.T) {
	d := newDoQServer(t, [4]byte{5, 5, 5, 5})
	fc, _ := d.client(t)
	r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	q := dnsQuery("example.com")
	binary.BigEndian.PutUint16(q[:2], 0x5151)
	resp, err := r.exchange(ctx, q)
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if d.wireID.Load() != 0 {
		t.Errorf("wire ID = %#x, want 0", d.wireID.Load())
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0x5151 {
		t.Errorf("txid = %#x, want the caller's 0x5151 restored", binary.BigEndian.Uint16(resp[:2]))
	}
	if resp[len(resp)-1] != 5 {
		t.Errorf("answer = %v", resp)
	}
	if r.id() != "quic|"+d.server() {
		t.Errorf("id = %q", r.id())
	}
}

func TestDoQResolver_reusesTheConnection(t *testing.T) {
	d := newDoQServer(t, [4]byte{5, 5, 5, 5})
	fc, bridges := d.client(t)
	r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for i := 0; i < 3; i++ {
		if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
	}
	if n := len(bridges()); n != 1 {
		t.Errorf("opened %d UDP sessions, want 1", n)
	}
	if d.requests.Load() != 3 {
		t.Errorf("server saw %d queries, want 3", d.requests.Load())
	}
}

func TestDoQResolver_redialsAfterTheConnectionDies(t *testing.T) {
	d := newDoQServer(t, [4]byte{5, 5, 5, 5})
	fc, bridges := d.client(t)
	r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}
	if n := len(bridges()); n != 1 {
		t.Fatalf("opened %d UDP sessions, want 1", n)
	}
	bridges()[0].Close()
	time.Sleep(100 * time.Millisecond)

	if _, err := r.exchange(ctx, dnsQuery("example.org")); err != nil {
		t.Fatalf("exchange after the connection died: %v", err)
	}
	if n := len(bridges()); n != 2 {
		t.Errorf("opened %d UDP sessions, want 2 (redial)", n)
	}
}

func TestDoQResolver_udpDisabledFallsBackToDoT(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{6, 6, 6, 6})
	})
	// No udp func: the fake client reports the relay as unavailable.
	fc := &fakeClient{tcp: func(string) (net.Conn, error) { return dial() }}
	r := newDoQResolver(fc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for i := 0; i < 2; i++ {
		resp, err := r.exchange(ctx, dnsQuery("example.com"))
		if err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
		if resp[len(resp)-1] != 6 {
			t.Errorf("answer = %v", resp)
		}
	}
	if !r.isUDPDown() {
		t.Error("resolver should remember that the UDP relay is unavailable")
	}
}

func TestDoQResolver_blackholeSwitchesToDoT(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{6, 6, 6, 6})
	})
	fc := &fakeClient{
		tcp: func(string) (net.Conn, error) { return dial() },
		udp: func() (client.HyUDPConn, error) { return newFakeUDPConn(nil), nil },
	}
	r := newDoQResolver(fc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()
	r.qcfg.HandshakeIdleTimeout = 200 * time.Millisecond

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for i := 0; i < fallbackGateThreshold; i++ {
		if _, err := r.exchange(ctx, dnsQuery("example.com")); err == nil {
			t.Fatalf("exchange %d should fail against a blackholed relay", i)
		}
	}
	if r.isUDPDown() {
		t.Fatal("resolver must not switch before the fallback answers")
	}
	resp, err := r.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange after the gate tripped: %v", err)
	}
	if resp[len(resp)-1] != 6 {
		t.Errorf("answer = %v", resp)
	}
	if !r.isUDPDown() {
		t.Error("resolver should stay on DoT after the fallback answered")
	}
}

func TestDoQResolver_newSessionRetriesDoQ(t *testing.T) {
	cert, pool := testCert(t)
	dial := loopbackDoTServer(t, cert, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{6, 6, 6, 6})
	})
	udpCalls := 0
	sc := &seqClient{fakeClient: &fakeClient{
		tcp: func(string) (net.Conn, error) { return dial() },
		udp: func() (client.HyUDPConn, error) {
			udpCalls++
			return newFakeUDPConn(nil), nil
		},
	}}
	r := newDoQResolver(sc, "dns.test:853", &tls.Config{RootCAs: pool})
	defer r.close()
	r.qcfg.HandshakeIdleTimeout = 200 * time.Millisecond

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for i := 0; i < fallbackGateThreshold; i++ {
		_, _ = r.exchange(ctx, dnsQuery("example.com"))
	}
	if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("fallback exchange: %v", err)
	}
	if !r.isUDPDown() {
		t.Fatal("gate switch expected")
	}
	before := udpCalls
	sc.seq.Add(1)
	_, _ = r.exchange(ctx, dnsQuery("example.org"))
	if udpCalls <= before {
		t.Error("a new session should retry DoQ")
	}
}
