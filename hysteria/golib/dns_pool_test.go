package golib

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/quic-go"
)

type streamFault int

const (
	faultAnswer streamFault = iota
	faultAnswerThenClose
	faultSilent
	faultHalfLength
	faultHalfBody
)

type faultDNSServer struct {
	ln      net.Listener
	conns   atomic.Int32
	queries atomic.Int32
	release chan struct{}
}

func newFaultDNSServer(t *testing.T, fault func(conn, query int) streamFault) *faultDNSServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &faultDNSServer{ln: ln, release: make(chan struct{})}
	t.Cleanup(func() {
		close(s.release)
		ln.Close()
	})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serve(c, int(s.conns.Add(1)), fault)
		}
	}()
	return s
}

func (s *faultDNSServer) serve(c net.Conn, id int, fault func(conn, query int) streamFault) {
	defer c.Close()
	for n := 1; ; n++ {
		q, err := readDNSFrame(c)
		if err != nil {
			return
		}
		s.queries.Add(1)
		resp := dnsResponseFor(q, 60, [4]byte{byte(id), 0, 0, byte(n)})
		switch fault(id, n) {
		case faultAnswer:
			if writeDNSFrame(c, resp) != nil {
				return
			}
			continue
		case faultAnswerThenClose:
			_ = writeDNSFrame(c, resp)
			return
		case faultHalfLength:
			_, _ = c.Write([]byte{0x00})
		case faultHalfBody:
			frame := make([]byte, 2+len(resp)/2)
			binary.BigEndian.PutUint16(frame, uint16(len(resp)))
			copy(frame[2:], resp)
			_, _ = c.Write(frame)
		}
		<-s.release
		return
	}
}

func (s *faultDNSServer) connect(string) (net.Conn, error) {
	return net.Dial("tcp", s.ln.Addr().String())
}

func (s *faultDNSServer) dial(context.Context) (net.Conn, error) {
	return s.connect("")
}

func (s *faultDNSServer) client() *fakeClient {
	return &fakeClient{tcp: s.connect}
}

func answerConn(resp []byte) int {
	return int(resp[len(resp)-4])
}

func fillPool(t *testing.T, p *streamPool, n int) {
	t.Helper()
	warmed := make([]*pooledConn, 0, n)
	for i := 0; i < n; i++ {
		if _, err := p.exchange(context.Background(), dnsQuery(fmt.Sprintf("warm%d.example", i))); err != nil {
			t.Fatalf("warm-up %d: %v", i, err)
		}
		select {
		case c := <-p.idle:
			warmed = append(warmed, c)
		default:
			t.Fatalf("warm-up %d left no stream in the pool", i)
		}
	}
	for _, c := range warmed {
		p.idle <- c
	}
}

func loopbackTCPDNSServer(t *testing.T, respond func(conn int, query []byte) []byte) func() (net.Conn, error) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		n := 0
		for {
			s, err := ln.Accept()
			if err != nil {
				return
			}
			n++
			go func(s net.Conn, id int) {
				defer s.Close()
				for {
					q, err := readDNSFrame(s)
					if err != nil {
						return
					}
					resp := respond(id, q)
					if resp == nil {
						return
					}
					if err := writeDNSFrame(s, resp); err != nil {
						return
					}
				}
			}(s, n)
		}
	}()
	return func() (net.Conn, error) { return net.Dial("tcp", ln.Addr().String()) }
}

func TestTCPResolver_roundTrip(t *testing.T) {
	dial := loopbackTCPDNSServer(t, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
	})
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		if addr != "1.1.1.1:53" {
			t.Errorf("dialed %q", addr)
		}
		return dial()
	}}
	r := newTCPResolver(fc, "1.1.1.1:53")
	defer r.close()

	resp, err := r.exchange(context.Background(), dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 1 {
		t.Errorf("answer = %v", resp)
	}
	if r.id() != "tcp|1.1.1.1:53" {
		t.Errorf("id = %q", r.id())
	}
}

func TestTCPResolver_reusesPooledConn(t *testing.T) {
	dial := loopbackTCPDNSServer(t, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
	})
	var dialed atomic.Int32
	fc := &fakeClient{tcp: func(string) (net.Conn, error) {
		dialed.Add(1)
		return dial()
	}}
	r := newTCPResolver(fc, "1.1.1.1:53")
	defer r.close()

	for i := 0; i < 5; i++ {
		if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
			t.Fatalf("exchange %d: %v", i, err)
		}
	}
	if dialed.Load() != 1 {
		t.Errorf("dialed %d times, want 1 (pooled connection reused)", dialed.Load())
	}
}

func TestTCPResolver_retriesOnStalePooledConn(t *testing.T) {
	var answers atomic.Int32
	dial := loopbackTCPDNSServer(t, func(conn int, q []byte) []byte {
		if conn == 1 && answers.Add(1) > 1 {
			return nil
		}
		return dnsResponseFor(q, 60, [4]byte{byte(conn), 0, 0, 0})
	})
	var dialed atomic.Int32
	fc := &fakeClient{tcp: func(string) (net.Conn, error) {
		dialed.Add(1)
		return dial()
	}}
	r := newTCPResolver(fc, "1.1.1.1:53")
	defer r.close()

	if _, err := r.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}
	resp, err := r.exchange(context.Background(), dnsQuery("example.org"))
	if err != nil {
		t.Fatalf("second exchange should redial: %v", err)
	}
	if dialed.Load() != 2 {
		t.Errorf("dialed %d times, want 2", dialed.Load())
	}
	if resp[len(resp)-4] != 2 {
		t.Errorf("answer came from connection %d, want 2", resp[len(resp)-4])
	}
}

func TestStreamPool_dropsIdleConnPastTimeout(t *testing.T) {
	dial := loopbackTCPDNSServer(t, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
	})
	var dialed atomic.Int32
	p := newStreamPool("test", func(context.Context) (net.Conn, error) {
		dialed.Add(1)
		return dial()
	})
	defer p.close()

	if _, err := p.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("first exchange: %v", err)
	}
	c := <-p.idle
	c.last = time.Now().Add(-dnsPoolIdleTimeout - time.Second)
	p.idle <- c

	if _, err := p.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("second exchange: %v", err)
	}
	if dialed.Load() != 2 {
		t.Errorf("dialed %d times, want 2 (stale connection discarded)", dialed.Load())
	}
}

func TestStreamPool_closeClosesIdleConns(t *testing.T) {
	dial := loopbackTCPDNSServer(t, func(_ int, q []byte) []byte {
		return dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1})
	})
	p := newStreamPool("test", func(context.Context) (net.Conn, error) { return dial() })
	if _, err := p.exchange(context.Background(), dnsQuery("example.com")); err != nil {
		t.Fatalf("exchange: %v", err)
	}
	p.close()
	if len(p.idle) != 0 {
		t.Errorf("pool still holds %d connections after close", len(p.idle))
	}
}

func TestStreamPool_retiresStreamsThatStallMidResponse(t *testing.T) {
	for _, tc := range []struct {
		name  string
		fault streamFault
	}{
		{"no response", faultSilent},
		{"half the length prefix", faultHalfLength},
		{"half the body", faultHalfBody},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := newFaultDNSServer(t, func(conn, _ int) streamFault {
				if conn == 1 {
					return tc.fault
				}
				return faultAnswer
			})
			p := newStreamPool("test", srv.dial)
			defer p.close()

			ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
			defer cancel()
			start := time.Now()
			_, err := p.exchange(ctx, dnsQuery("example.com"))
			if !isTimeoutClass(err) {
				t.Fatalf("err = %v, want a timeout", err)
			}
			if elapsed := time.Since(start); elapsed > time.Second {
				t.Errorf("stalled read took %v, want it ended by the 300ms deadline", elapsed)
			}
			if len(p.idle) != 0 {
				t.Error("a stalled stream went back to the pool")
			}
			resp, err := p.exchange(context.Background(), dnsQuery("example.org"))
			if err != nil {
				t.Fatalf("next query: %v", err)
			}
			if conn := answerConn(resp); conn != 2 {
				t.Errorf("answer came from connection %d, want a new connection 2", conn)
			}
		})
	}
}

type closeSignalConn struct {
	net.Conn
	once   sync.Once
	closed chan struct{}
}

func (c *closeSignalConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return c.Conn.Close()
}

func TestTCPResolver_poolsAStreamThatOpensAfterItsCallerLeft(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	dialing := make(chan struct{}, 1)
	release := make(chan struct{})
	var dialed atomic.Int32
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		dialed.Add(1)
		dialing <- struct{}{}
		<-release
		return srv.connect(addr)
	}}
	r := newTCPResolver(fc, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-dialing
		cancel()
	}()
	start := time.Now()
	_, err := r.exchange(ctx, dnsQuery("slow.example"))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("a cancel while the stream was opening took %v", elapsed)
	}

	close(release)
	select {
	case c := <-r.pool.idle:
		r.pool.idle <- c
	case <-time.After(2 * time.Second):
		t.Fatal("the stream that finished opening after its caller left never reached the pool")
	}
	resp, err := r.exchange(context.Background(), dnsQuery("next.example"))
	if err != nil {
		t.Fatalf("the next query: %v", err)
	}
	if n := dialed.Load(); n != 1 {
		t.Errorf("opened %d streams, want the one the first query started to serve the next", n)
	}
	if conn := answerConn(resp); conn != 1 {
		t.Errorf("answer came from connection %d, want the late stream 1", conn)
	}
	if q := srv.queries.Load(); q != 1 {
		t.Errorf("server saw %d queries, want only the next query's", q)
	}
}

func TestStreamPool_closeCancelsTheOpensInFlight(t *testing.T) {
	dialing := make(chan struct{}, 1)
	returned := make(chan struct{})
	p := newStreamPool("test", func(ctx context.Context) (net.Conn, error) {
		defer close(returned)
		dialing <- struct{}{}
		<-ctx.Done()
		return nil, ctx.Err()
	})
	defer p.close()

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		<-dialing
		cancel()
	}()
	if _, err := p.exchange(ctx, dnsQuery("example.com")); !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	select {
	case <-returned:
		t.Fatal("the open ended with the query that started it, want it kept going for the pool")
	default:
	}
	p.close()
	select {
	case <-returned:
	case <-time.After(2 * time.Second):
		t.Fatal("closing the pool left its open running")
	}
}

func TestStreamPool_closesAStreamThatFinishesOpeningAfterClose(t *testing.T) {
	dialing := make(chan struct{}, 1)
	release := make(chan struct{})
	opened := make(chan *closeSignalConn, 1)
	p := newStreamPool("test", func(context.Context) (net.Conn, error) {
		dialing <- struct{}{}
		<-release
		client, server := net.Pipe()
		t.Cleanup(func() { server.Close() })
		conn := &closeSignalConn{Conn: client, closed: make(chan struct{})}
		opened <- conn
		return conn, nil
	})
	defer p.close()

	ctx, cancel := context.WithCancel(context.Background())
	result := make(chan error, 1)
	go func() {
		_, err := p.exchange(ctx, dnsQuery("example.com"))
		result <- err
	}()
	<-dialing
	cancel()
	select {
	case <-result:
	case <-time.After(2 * time.Second):
		close(release)
		t.Fatal("the caller waited for its stream to open after it was cancelled")
	}
	p.close()
	close(release)
	select {
	case <-(<-opened).closed:
	case <-time.After(2 * time.Second):
		t.Fatal("a stream that finished opening after the pool closed was left open")
	}
	if len(p.idle) != 0 {
		t.Error("a stream that finished opening after the pool closed went into the pool")
	}
}

func TestStreamPool_givesUpAnOpenAtTheOpenTimeout(t *testing.T) {
	p := newStreamPool("DNS over TCP 1.1.1.1:53", func(ctx context.Context) (net.Conn, error) {
		<-ctx.Done()
		return nil, fmt.Errorf("dial DNS server 1.1.1.1:53: %w", ctx.Err())
	})
	defer p.close()
	p.openTimeout = 100 * time.Millisecond

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	_, err := p.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, context.DeadlineExceeded) || !strings.Contains(fmt.Sprint(err), "dial DNS server 1.1.1.1:53") {
		t.Errorf("err = %v, want the open's own timeout", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Errorf("a hung open held the query for %v, want it given up at the %v open timeout", elapsed, p.openTimeout)
	}
}

func TestStreamPool_aHedgeThatLosesStillPoolsItsStream(t *testing.T) {
	hedgeDialing := make(chan struct{})
	var armed atomic.Bool
	dial := loopbackTCPDNSServer(t, func(conn int, q []byte) []byte {
		if conn == 1 && armed.Load() {
			<-hedgeDialing
		}
		return dnsResponseFor(q, 60, [4]byte{byte(conn), 0, 0, 0})
	})
	release := make(chan struct{})
	var dials atomic.Int32
	p := newStreamPool("test", func(context.Context) (net.Conn, error) {
		if dials.Add(1) == 2 {
			close(hedgeDialing)
			<-release
		}
		return dial()
	})
	defer p.close()
	fillPool(t, p, 1)
	armed.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	resp, err := p.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("the pooled stream answers, so the query must succeed: %v", err)
	}
	if conn := answerConn(resp); conn != 1 {
		t.Fatalf("answer came from connection %d, want the pooled stream 1", conn)
	}
	close(release)
	for held := 0; held < 2; held++ {
		select {
		case <-p.idle:
		case <-time.After(2 * time.Second):
			t.Fatalf("pool holds %d streams, want the pooled stream and the one the losing hedge opened", held)
		}
	}
}

func TestStreamPool_concurrentQueriesKeepTheirAnswers(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	p := newStreamPool("test", srv.dial)
	defer p.close()
	fillPool(t, p, dnsPoolSize)
	warmConns := int(srv.conns.Load())

	const rounds, perRound = 4, 2 * dnsPoolSize
	for round := 0; round < rounds; round++ {
		var wg sync.WaitGroup
		for i := round * perRound; i < (round+1)*perRound; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				name := fmt.Sprintf("host%d.example", i)
				query := dnsQuery(name)
				binary.BigEndian.PutUint16(query[:2], uint16(0x4000+i))
				resp, err := p.exchange(context.Background(), query)
				if err != nil {
					t.Errorf("%s: %v", name, err)
					return
				}
				got, _ := dnsQuestion(resp)
				want, _ := dnsQuestion(query)
				if got != want || binary.BigEndian.Uint16(resp[:2]) != uint16(0x4000+i) {
					t.Errorf("%s received the answer to another query", name)
				}
			}(i)
		}
		wg.Wait()
	}
	if reused := rounds*perRound - (int(srv.conns.Load()) - warmConns); reused < rounds*dnsPoolSize {
		t.Errorf("%d of %d queries rode a pooled stream, want at least %d", reused, rounds*perRound, rounds*dnsPoolSize)
	}
	if held := len(p.idle); held == 0 || held > dnsPoolSize {
		t.Errorf("pool holds %d streams after the burst, want between 1 and %d", held, dnsPoolSize)
	}
}

func TestStreamPool_burstOnAnEmptyPoolOpensAtMostTwoStreamsAtOnce(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	started := make(chan struct{}, 64)
	gate := make(chan struct{})
	var opening, peak atomic.Int32
	p := newStreamPool("test", func(ctx context.Context) (net.Conn, error) {
		n := opening.Add(1)
		for m := peak.Load(); n > m && !peak.CompareAndSwap(m, n); m = peak.Load() {
		}
		started <- struct{}{}
		<-gate
		opening.Add(-1)
		return srv.dial(ctx)
	})
	defer p.close()

	const burst = 8
	var wg sync.WaitGroup
	for i := 0; i < burst; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			query := dnsQuery(fmt.Sprintf("burst%d.example", i))
			resp, err := p.exchange(ctx, query)
			if err != nil {
				t.Errorf("query %d: %v", i, err)
				return
			}
			if got, _ := dnsQuestion(resp); got != string(query[12:]) {
				t.Errorf("query %d received the answer to another query", i)
			}
		}(i)
	}
	for i := 0; i < dnsPoolMaxOpening; i++ {
		<-started
	}
	select {
	case <-started:
	case <-time.After(200 * time.Millisecond):
	}
	close(gate)
	wg.Wait()
	if n := peak.Load(); n > dnsPoolMaxOpening {
		t.Errorf("%d streams were opening at once, want at most %d", n, dnsPoolMaxOpening)
	}
}

func TestStreamPool_reportsWhetherTheFailedStreamWasPooled(t *testing.T) {
	var stale atomic.Bool
	srv := newFaultDNSServer(t, func(int, int) streamFault {
		if stale.Load() {
			return faultSilent
		}
		return faultAnswer
	})
	p := newStreamPool("DNS over TCP 1.1.1.1:53", srv.dial)
	defer p.close()
	fillPool(t, p, 1)
	c := <-p.idle
	c.last = time.Now().Add(-12 * time.Second)
	c.opened = time.Now().Add(-45 * time.Second)
	p.idle <- c
	stale.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	_, err := p.exchange(ctx, dnsQuery("example.com"))
	pattern := `^DNS over TCP 1\.1\.1\.1:53: pooled stream idle 12s \(open 45s, answered 1\) failed: no response after \S+: context deadline exceeded; new stream dialed in \S+: no response after \S+: context deadline exceeded$`
	if msg := fmt.Sprint(err); !regexp.MustCompile(pattern).MatchString(msg) {
		t.Errorf("err = %q, want it to match %q", msg, pattern)
	}
	if !isTimeoutClass(err) {
		t.Errorf("err = %v, want it still classified as a timeout", err)
	}
}

func TestStreamPool_reportsHowLongANewStreamTookToDial(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultSilent })
	p := newStreamPool("DoT dns.test:853", func(ctx context.Context) (net.Conn, error) {
		time.Sleep(200 * time.Millisecond)
		return srv.dial(ctx)
	})
	defer p.close()

	ctx, cancel := context.WithTimeout(context.Background(), 600*time.Millisecond)
	defer cancel()
	_, err := p.exchange(ctx, dnsQuery("example.com"))
	match := regexp.MustCompile(`^DoT dns\.test:853: new stream dialed in (\S+): no response after `).FindStringSubmatch(fmt.Sprint(err))
	if match == nil {
		t.Fatalf("err = %v, want the new stream and its dial time named", err)
	}
	if dialed, perr := time.ParseDuration(match[1]); perr != nil || dialed < 200*time.Millisecond || dialed > 500*time.Millisecond {
		t.Errorf("reported dial time %q, want the 200ms the dial took", match[1])
	}
	if !isTimeoutClass(err) {
		t.Errorf("err = %v, want a timeout", err)
	}
}

func TestStreamPool_reportsAClosedPooledStreamBeforeTheRedialError(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswerThenClose })
	errRedial := errors.New("redial refused")
	var dialed atomic.Int32
	p := newStreamPool("DoT dns.test:853", func(ctx context.Context) (net.Conn, error) {
		if dialed.Add(1) > 1 {
			return nil, errRedial
		}
		return srv.dial(ctx)
	})
	defer p.close()
	fillPool(t, p, 1)
	time.Sleep(100 * time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, err := p.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, errRedial) {
		t.Fatalf("err = %v, want the redial error kept for errors.Is", err)
	}
	msg := fmt.Sprint(err)
	if !strings.HasPrefix(msg, "DoT dns.test:853: pooled stream idle ") || !strings.Contains(msg, " failed: ") || !strings.HasSuffix(msg, "; redial refused") {
		t.Errorf("err = %q, want the closed pooled stream reported before the redial error", msg)
	}
}

func TestStreamPool_retriesAStalePooledStreamWithinTheAttempt(t *testing.T) {
	var stale atomic.Bool
	srv := newFaultDNSServer(t, func(conn, _ int) streamFault {
		if stale.Load() && conn == 1 {
			return faultSilent
		}
		return faultAnswer
	})
	p := newStreamPool("test", srv.dial)
	defer p.close()
	fillPool(t, p, 1)
	stale.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	resp, err := p.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("a new stream answers, so the query must succeed: %v", err)
	}
	if conn := answerConn(resp); conn != 2 {
		t.Errorf("answer came from connection %d, want a new connection 2", conn)
	}
}

func TestStreamPool_keepsTheRedialErrorAfterAStalePooledStream(t *testing.T) {
	var stale atomic.Bool
	srv := newFaultDNSServer(t, func(int, int) streamFault {
		if stale.Load() {
			return faultSilent
		}
		return faultAnswer
	})
	errRedial := errors.New("redial refused")
	p := newStreamPool("DoT dns.test:853", func(ctx context.Context) (net.Conn, error) {
		if stale.Load() {
			return nil, errRedial
		}
		return srv.dial(ctx)
	})
	defer p.close()
	fillPool(t, p, 1)
	stale.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	_, err := p.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, errRedial) {
		t.Fatalf("err = %v, want the redial error kept", err)
	}
	if msg := fmt.Sprint(err); !strings.HasPrefix(msg, "DoT dns.test:853: pooled stream idle ") || !strings.Contains(msg, " failed: no response after ") {
		t.Errorf("err = %q, want the stale pooled stream reported before the redial error", msg)
	}
}

func TestStreamPool_dropsIdleStreamsWhenAStaleOneTimesOut(t *testing.T) {
	var stale atomic.Bool
	srv := newFaultDNSServer(t, func(conn, _ int) streamFault {
		if stale.Load() && conn <= 3 {
			return faultSilent
		}
		return faultAnswer
	})
	p := newStreamPool("test", srv.dial)
	defer p.close()
	fillPool(t, p, 3)
	stale.Store(true)
	before := srv.queries.Load()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, err := p.exchange(ctx, dnsQuery("example.com")); err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if held := len(p.idle); held != 1 {
		t.Errorf("pool holds %d streams, want only the new one", held)
	}
	if sent := srv.queries.Load() - before; sent != 2 {
		t.Errorf("server saw %d queries, want the stale attempt and its retry only", sent)
	}
}

func TestStreamPool_keepsIdleStreamsWhenTheRetryAlsoTimesOut(t *testing.T) {
	var dead atomic.Bool
	srv := newFaultDNSServer(t, func(int, int) streamFault {
		if dead.Load() {
			return faultSilent
		}
		return faultAnswer
	})
	p := newStreamPool("test", srv.dial)
	defer p.close()
	fillPool(t, p, 3)
	dead.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if _, err := p.exchange(ctx, dnsQuery("example.com")); err == nil {
		t.Fatal("every stream is silent, so the query must fail")
	}
	if held := len(p.idle); held != 2 {
		t.Errorf("pool holds %d streams, want the 2 untouched ones kept for a tunnel replacement to fail fast", held)
	}
}

type signallingDialer struct {
	connect func() (net.Conn, error)
	dialed  atomic.Int32
	opened  chan *closeSignalConn
}

func newSignallingDialer(connect func() (net.Conn, error)) *signallingDialer {
	return &signallingDialer{connect: connect, opened: make(chan *closeSignalConn, 8)}
}

func (d *signallingDialer) dial(context.Context) (net.Conn, error) {
	conn, err := d.connect()
	if err != nil {
		return nil, err
	}
	d.dialed.Add(1)
	signal := &closeSignalConn{Conn: conn, closed: make(chan struct{})}
	d.opened <- signal
	return signal, nil
}

type heldAnswers struct {
	holding  atomic.Bool
	conn     int
	received chan int
	release  chan struct{}
}

func newHeldAnswers(conn int) *heldAnswers {
	return &heldAnswers{conn: conn, received: make(chan int, 8), release: make(chan struct{})}
}

func (h *heldAnswers) respond(conn int, q []byte) []byte {
	if h.holding.Load() && (h.conn == 0 || h.conn == conn) {
		h.received <- conn
		<-h.release
	}
	return dnsResponseFor(q, 60, [4]byte{byte(conn), 0, 0, 0})
}

func collectLateAnswers(ctx context.Context) (context.Context, chan []byte) {
	late := make(chan []byte, 4)
	return withLateAnswer(ctx, func(resp []byte) { late <- resp }), late
}

func TestStreamPool_readOutlivesACancelledQueryAndPoolsItsStream(t *testing.T) {
	for _, tc := range []struct {
		name   string
		pooled bool
	}{
		{"new stream", false},
		{"pooled stream", true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			held := newHeldAnswers(0)
			t.Cleanup(func() {
				held.holding.Store(false)
				select {
				case <-held.release:
				default:
					close(held.release)
				}
			})
			dialer := newSignallingDialer(loopbackTCPDNSServer(t, held.respond))
			p := newStreamPool("test", dialer.dial)
			defer p.close()
			if tc.pooled {
				fillPool(t, p, 1)
			}
			held.holding.Store(true)

			base, late := collectLateAnswers(context.Background())
			ctx, cancel := context.WithCancel(base)
			go func() {
				<-held.received
				cancel()
			}()
			start := time.Now()
			_, err := p.exchange(ctx, dnsQuery("late.example"))
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("err = %v, want context.Canceled", err)
			}
			if elapsed := time.Since(start); elapsed > time.Second {
				t.Errorf("a cancel during the read took %v to return", elapsed)
			}
			stream := <-dialer.opened
			select {
			case <-stream.closed:
				t.Fatal("the stream was closed with the query that sent on it, want its read kept going")
			default:
			}

			held.holding.Store(false)
			close(held.release)
			select {
			case resp := <-late:
				if got, _ := dnsQuestion(resp); got != string(dnsQuery("late.example")[12:]) {
					t.Errorf("late answer is for %q, want the cancelled query's", got)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("the answer that arrived after its query left never reached the late-answer hook")
			}
			select {
			case c := <-p.idle:
				if c.conn != net.Conn(stream) {
					t.Error("the pool holds another stream, want the one that answered late")
				}
			case <-time.After(2 * time.Second):
				t.Fatal("the stream that answered after its query left never went back to the pool")
			}
			if dialed := dialer.dialed.Load(); dialed != 1 {
				t.Errorf("%d streams dialled, want only the one the query used", dialed)
			}
		})
	}
}

func TestStreamPool_keepsReadingTheLosingStreamAfterAnotherAnswers(t *testing.T) {
	held := newHeldAnswers(1)
	t.Cleanup(func() {
		held.holding.Store(false)
		select {
		case <-held.release:
		default:
			close(held.release)
		}
	})
	dialer := newSignallingDialer(loopbackTCPDNSServer(t, held.respond))
	p := newStreamPool("test", dialer.dial)
	defer p.close()
	fillPool(t, p, 1)
	pooled := <-dialer.opened
	held.holding.Store(true)

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	resp, err := p.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("a new stream answers, so the query must succeed: %v", err)
	}
	if conn := answerConn(resp); conn != 2 {
		t.Fatalf("answer came from connection %d, want the new stream 2", conn)
	}
	select {
	case <-pooled.closed:
		t.Fatal("the pooled stream was closed once the new stream answered, want its read kept going")
	default:
	}

	close(held.release)
	back := false
	for i := 0; i < 2 && !back; i++ {
		select {
		case c := <-p.idle:
			back = c.conn == net.Conn(pooled)
		case <-time.After(2 * time.Second):
			t.Fatal("the pool is missing a stream that answered")
		}
	}
	if !back {
		t.Error("the slow pooled stream answered but never went back to the pool")
	}
}

type deadlineRecordingConn struct {
	net.Conn
	mu        sync.Mutex
	deadlines []time.Time
}

func (c *deadlineRecordingConn) SetDeadline(t time.Time) error {
	c.mu.Lock()
	c.deadlines = append(c.deadlines, t)
	c.mu.Unlock()
	return c.Conn.SetDeadline(t)
}

func (c *deadlineRecordingConn) readDeadline() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, d := range c.deadlines {
		if !d.IsZero() {
			return d
		}
	}
	return time.Time{}
}

func TestStreamPool_readDeadlineOutlastsAShortCaller(t *testing.T) {
	dial := loopbackTCPDNSServer(t, func(_ int, q []byte) []byte { return dnsResponseFor(q, 60, [4]byte{1, 1, 1, 1}) })
	for _, tc := range []struct {
		name   string
		budget time.Duration
		late   bool
	}{
		{"short caller", 100 * time.Millisecond, true},
		{"long caller", 20 * time.Second, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			recorded := make(chan *deadlineRecordingConn, 1)
			p := newStreamPool("test", func(context.Context) (net.Conn, error) {
				conn, err := dial()
				if err != nil {
					return nil, err
				}
				c := &deadlineRecordingConn{Conn: conn}
				recorded <- c
				return c, nil
			})
			defer p.close()

			ctx, cancel := context.WithTimeout(context.Background(), tc.budget)
			defer cancel()
			callerDeadline, _ := ctx.Deadline()
			sent := time.Now()
			if _, err := p.exchange(ctx, dnsQuery("example.com")); err != nil {
				t.Fatalf("exchange: %v", err)
			}
			answered := time.Now()
			got := (<-recorded).readDeadline()
			if !tc.late {
				if !got.Equal(callerDeadline) {
					t.Errorf("read deadline %v, want the caller's own deadline %v", got, callerDeadline)
				}
				return
			}
			if got.Before(sent.Add(dnsLateReadTimeout)) || got.After(answered.Add(dnsLateReadTimeout)) {
				t.Errorf("read deadline %v after the send, want %v", got.Sub(sent), dnsLateReadTimeout)
			}
		})
	}
}

type shortBudgetResolver struct {
	dnsResolver
	budget time.Duration
	stored chan struct{}
}

func (r *shortBudgetResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	store := lateAnswer(ctx)
	ctx = withLateAnswer(ctx, func(resp []byte) {
		store(resp)
		r.stored <- struct{}{}
	})
	ctx, cancel := context.WithTimeout(ctx, r.budget)
	defer cancel()
	return r.dnsResolver.exchange(ctx, query)
}

func TestDNSCacheResolve_servesTheRetryFromAStreamThatAnsweredLate(t *testing.T) {
	held := newHeldAnswers(0)
	t.Cleanup(func() {
		select {
		case <-held.release:
		default:
			close(held.release)
		}
	})
	held.holding.Store(true)
	var queries atomic.Int32
	dial := loopbackTCPDNSServer(t, func(conn int, q []byte) []byte {
		queries.Add(1)
		return held.respond(conn, q)
	})
	tcp := newTCPResolver(&fakeClient{tcp: func(string) (net.Conn, error) { return dial() }}, "1.1.1.1:53")
	defer tcp.close()
	r := &shortBudgetResolver{dnsResolver: tcp, budget: 200 * time.Millisecond, stored: make(chan struct{}, 1)}
	c := newDNSCache()

	if _, err := c.resolve(context.Background(), r, dnsQuery("example.com"), nil); !isTimeoutClass(err) {
		t.Fatalf("err = %v, want the query to run out of its budget", err)
	}
	held.holding.Store(false)
	close(held.release)
	select {
	case <-r.stored:
	case <-time.After(2 * time.Second):
		t.Fatal("the answer that arrived after the query gave up never reached the cache")
	}

	retry := dnsQuery("example.com")
	binary.BigEndian.PutUint16(retry[:2], 0x5151)
	resp, err := c.resolve(context.Background(), r, retry, nil)
	if err != nil {
		t.Fatalf("the retry: %v", err)
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0x5151 || answerConn(resp) != 1 {
		t.Errorf("retry answer = %v, want the late answer under the retry's ID", resp)
	}
	if n := queries.Load(); n != 1 {
		t.Errorf("server saw %d queries, want the retry served from the cache", n)
	}
}

type closeRecordingConn struct {
	net.Conn
	closed atomic.Bool
}

func (c *closeRecordingConn) Close() error {
	c.closed.Store(true)
	return nil
}

func TestStreamPool_closeRacingAReturnedStreamClosesIt(t *testing.T) {
	if runtime.GOMAXPROCS(0) < 2 {
		t.Skip("the race needs two threads")
	}
	const rounds = 200000
	leaked := 0
	for i := 0; i < rounds; i++ {
		p := newStreamPool("test", nil)
		conn := &closeRecordingConn{}
		returned := make(chan struct{})
		go func() {
			p.put(&pooledConn{conn: conn, last: time.Now()})
			close(returned)
		}()
		p.close()
		<-returned
		if !conn.closed.Load() {
			leaked++
		}
	}
	if leaked > 0 {
		t.Errorf("%d of %d streams returned while the pool closed were left open", leaked, rounds)
	}
}

type failingConn struct {
	net.Conn
	err error
}

func (c failingConn) Read([]byte) (int, error)    { return 0, c.err }
func (c failingConn) Write(b []byte) (int, error) { return len(b), nil }
func (c failingConn) Close() error                { return nil }
func (c failingConn) SetDeadline(time.Time) error { return nil }

func TestStreamPool_redialsStreamsAQUICIdleTimeoutClosed(t *testing.T) {
	srv := newFaultDNSServer(t, func(int, int) streamFault { return faultAnswer })
	p := newStreamPool("test", srv.dial)
	defer p.close()
	for i := 0; i < dnsPoolSize; i++ {
		p.idle <- &pooledConn{conn: failingConn{err: &quic.IdleTimeoutError{}}, opened: time.Now(), last: time.Now()}
	}

	for i := 0; i < dnsPoolSize; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_, err := p.exchange(ctx, dnsQuery("example.com"))
		cancel()
		if err != nil {
			t.Fatalf("lookup %d: a new stream answers, so a pooled one the idle timeout closed must not fail the query: %v", i, err)
		}
	}
	if dialed := srv.conns.Load(); dialed != dnsPoolSize {
		t.Errorf("dialled %d streams, want one per closed pooled stream", dialed)
	}
}

func TestStreamPool_skipsTheRedialWhenAPooledStreamReachesItsDeadline(t *testing.T) {
	var dialed atomic.Int32
	p := newStreamPool("test", func(context.Context) (net.Conn, error) {
		dialed.Add(1)
		return nil, errors.New("dial refused")
	})
	defer p.close()
	expired := &net.OpError{Op: "read", Net: "tcp", Err: os.ErrDeadlineExceeded}
	p.idle <- &pooledConn{conn: failingConn{err: expired}, opened: time.Now(), last: time.Now()}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_, err := p.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, os.ErrDeadlineExceeded) {
		t.Errorf("err = %v, want the pooled stream's deadline error", err)
	}
	if n := dialed.Load(); n != 0 {
		t.Errorf("dialled %d streams after the pooled one reached its deadline, want none", n)
	}
}
