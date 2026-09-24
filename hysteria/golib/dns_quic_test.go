package golib

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"net"
	"strings"
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
	return newDoQServerWith(t, func(q []byte) []byte { return dnsResponseFor(q, 60, ip) })
}

func newDoQServerWith(t *testing.T, respond func(q []byte) []byte) *doqServer {
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
						resp := respond(q)
						if resp == nil {
							return
						}
						_ = writeDNSFrame(st, resp)
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

type gatedUDP struct {
	started chan struct{}
	gate    chan struct{}
	once    sync.Once
}

func gateUDP(fc *fakeClient) *gatedUDP {
	g := &gatedUDP{started: make(chan struct{}, 16), gate: make(chan struct{})}
	inner := fc.udp
	fc.udp = func() (client.HyUDPConn, error) {
		g.started <- struct{}{}
		<-g.gate
		return inner()
	}
	return g
}

func (g *gatedUDP) open() { g.once.Do(func() { close(g.gate) }) }

func TestDoQResolver_concurrentCallersShareOneDial(t *testing.T) {
	d := newDoQServer(t, [4]byte{5, 5, 5, 5})
	fc, bridges := d.client(t)
	g := gateUDP(fc)
	r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
	defer r.close()

	const callers = 6
	var wg sync.WaitGroup
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			if _, err := r.exchange(ctx, dnsQuery("example.com")); err != nil {
				t.Errorf("caller %d: %v", i, err)
			}
		}(i)
	}
	<-g.started
	for waiting := true; waiting; {
		select {
		case <-g.started:
		case <-time.After(200 * time.Millisecond):
			waiting = false
		}
	}
	g.open()
	wg.Wait()
	if n := len(bridges()); n != 1 {
		t.Errorf("%d callers opened %d UDP sessions, want them to share one dial", callers, n)
	}
}

func TestDoQResolver_dialOutlivesTheCallerThatStartedIt(t *testing.T) {
	d := newDoQServer(t, [4]byte{5, 5, 5, 5})
	fc, bridges := d.client(t)
	g := gateUDP(fc)
	r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
	defer r.close()
	guard := time.AfterFunc(time.Second, g.open)
	defer guard.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	start := time.Now()
	_, err := r.exchange(ctx, dnsQuery("slow.example"))
	cancel()
	if !isTimeoutClass(err) {
		t.Errorf("err = %v, want the caller's timeout", err)
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Errorf("a caller whose connection was still dialing waited %v", elapsed)
	}
	g.open()

	next, cancelNext := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelNext()
	if _, err := r.exchange(next, dnsQuery("next.example")); err != nil {
		t.Fatalf("the next query: %v", err)
	}
	if n := len(bridges()); n != 1 {
		t.Errorf("opened %d UDP sessions, want the dial the first caller started to serve the next", n)
	}
}

func TestDoQResolver_closeCancelsTheDialInFlight(t *testing.T) {
	blackhole := newFakeUDPConn(nil)
	fc := &fakeClient{udp: func() (client.HyUDPConn, error) { return blackhole, nil }}
	r := newDoQResolver(fc, "dns.test:853", nil)
	defer r.close()

	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	_, _ = r.exchange(ctx, dnsQuery("example.com"))
	cancel()
	select {
	case <-blackhole.closed:
		t.Fatal("the dial ended with the caller that started it, want it kept going for the next query")
	default:
	}
	r.close()
	select {
	case <-blackhole.closed:
	case <-time.After(time.Second):
		t.Fatal("closing the resolver left its dial running")
	}
}

func TestDoQResolver_streamTimeoutKeepsTheConnection(t *testing.T) {
	release := blockUntilCleanup(t)
	d := newDoQServerWith(t, func(q []byte) []byte {
		if name, _ := dnsQuestion(q); strings.Contains(name, "slow") {
			<-release
			return nil
		}
		return dnsResponseFor(q, 60, [4]byte{5, 5, 5, 5})
	})
	fc, bridges := d.client(t)
	r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
	defer r.close()

	warm, cancelWarm := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelWarm()
	if _, err := r.exchange(warm, dnsQuery("warm.example")); err != nil {
		t.Fatalf("warm-up: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	_, err := r.exchange(ctx, dnsQuery("slow.example"))
	cancel()
	if !isTimeoutClass(err) {
		t.Fatalf("err = %v, want the slow query to time out", err)
	}
	next, cancelNext := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelNext()
	if _, err := r.exchange(next, dnsQuery("fast.example")); err != nil {
		t.Fatalf("a query after another timed out: %v", err)
	}
	if n := len(bridges()); n != 1 {
		t.Errorf("opened %d UDP sessions, want the connection kept when one stream timed out", n)
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

func TestDNSUpstream_movesPastADoQServerWhoseHandshakeTimesOut(t *testing.T) {
	fc := &fakeClient{udp: func() (client.HyUDPConn, error) { return newFakeUDPConn(nil), nil }}
	unreachable := newDoQResolver(fc, "dns.test:853", nil)
	unreachable.qcfg.HandshakeIdleTimeout = 100 * time.Millisecond
	working := &stubResolver{name: "quic|working.test:853", reply: echoAnswer([4]byte{2, 2, 2, 2})}
	up := &dnsUpstream{resolvers: []dnsResolver{unreachable, working}, ident: uniqueUpstreamID(t, "quic")}
	defer up.close()

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil || resp[len(resp)-1] != 2 {
		t.Fatalf("resp = %v, err = %v, want the working server's answer", resp, err)
	}
	if got := up.firstIndex(); got != 1 {
		t.Errorf("preferred = %d, want the working server once the other's handshake timed out", got)
	}
}

type pastDeadlineContext struct{ context.Context }

func (pastDeadlineContext) Deadline() (time.Time, bool) { return time.Now().Add(-time.Second), true }

func TestDoQResolver_aQueryOutOfTimeKeepsTheConnection(t *testing.T) {
	for _, tc := range []struct {
		name string
		ctx  func() context.Context
	}{
		{"cancelled", func() context.Context {
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			return ctx
		}},
		{"deadline passed before its timer fired", func() context.Context {
			return pastDeadlineContext{context.Background()}
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := newDoQServer(t, [4]byte{5, 5, 5, 5})
			fc, bridges := d.client(t)
			r := newDoQResolver(fc, d.server(), &tls.Config{RootCAs: d.pool})
			defer r.close()
			warm, cancelWarm := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancelWarm()
			if _, err := r.exchange(warm, dnsQuery("warm.example")); err != nil {
				t.Fatalf("warm-up: %v", err)
			}

			if _, err := r.exchange(tc.ctx(), dnsQuery("late.example")); err == nil {
				t.Fatal("a query with no time left must fail")
			}
			if _, err := r.exchange(warm, dnsQuery("next.example")); err != nil {
				t.Fatalf("the next query: %v", err)
			}
			if n := len(bridges()); n != 1 {
				t.Errorf("opened %d UDP sessions, want the connection kept when a query ran out of time", n)
			}
		})
	}
}
