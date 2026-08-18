package golib

import (
	"context"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

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
