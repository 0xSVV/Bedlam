package golib

import (
	"context"
	"encoding/binary"
	"fmt"
	"net"
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
