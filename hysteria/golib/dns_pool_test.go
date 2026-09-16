package golib

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
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

func TestTCPResolver_cancelDuringDialReturnsPromptly(t *testing.T) {
	release := make(chan struct{})
	late := make(chan *closeSignalConn, 1)
	fc := &fakeClient{tcp: func(string) (net.Conn, error) {
		<-release
		client, server := net.Pipe()
		t.Cleanup(func() { server.Close() })
		conn := &closeSignalConn{Conn: client, closed: make(chan struct{})}
		late <- conn
		return conn, nil
	}}
	r := newTCPResolver(fc, "1.1.1.1:53")
	defer r.close()

	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(50*time.Millisecond, cancel)
	start := time.Now()
	_, err := r.exchange(ctx, dnsQuery("example.com"))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("cancel during dial took %v", elapsed)
	}

	close(release)
	conn := <-late
	select {
	case <-conn.closed:
	case <-time.After(time.Second):
		t.Fatal("a stream that finished dialling after the cancel was never closed")
	}
	if len(r.pool.idle) != 0 {
		t.Error("a late stream went into the pool")
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
	msg := fmt.Sprint(err)
	for _, want := range []string{"DNS over TCP 1.1.1.1:53: pooled stream idle 12s (open 45s, answered 1)", "read response length"} {
		if !strings.Contains(msg, want) {
			t.Errorf("err = %q, want it to contain %q", msg, want)
		}
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
	match := regexp.MustCompile(`^DoT dns\.test:853: new stream dialed in (\S+): read response length: `).FindStringSubmatch(fmt.Sprint(err))
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
