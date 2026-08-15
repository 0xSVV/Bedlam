package golib

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"testing"
	"time"
)

func TestIsDNSPort(t *testing.T) {
	cases := []struct {
		addr string
		want bool
	}{
		{"1.1.1.1:53", true},
		{"[2001:db8::1]:53", true},
		{"1.1.1.1:54", false},
		{"1.1.1.1:5300", false},
		{"1.1.1.1", false},
		{"", false},
	}
	for _, c := range cases {
		if got := isDNSPort(c.addr); got != c.want {
			t.Errorf("isDNSPort(%q) = %v, want %v", c.addr, got, c.want)
		}
	}
}

func TestDNSStreamExchange_roundTrip(t *testing.T) {
	answer := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
	conn := pipeDNSServer(t, func(q []byte) []byte {
		resp := append([]byte(nil), answer...)
		copy(resp[:2], q[:2])
		return resp
	})
	defer conn.Close()

	query := dnsQuery("example.com")
	binary.BigEndian.PutUint16(query[:2], 0xbeef)
	resp, err := dnsStreamExchange(conn, query)
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if binary.BigEndian.Uint16(resp[:2]) != 0xbeef {
		t.Errorf("txid = %#x, want 0xbeef", binary.BigEndian.Uint16(resp[:2]))
	}
	if len(resp) != len(answer) {
		t.Errorf("response length = %d, want %d", len(resp), len(answer))
	}
}

func TestDNSStreamExchange_txidMismatch(t *testing.T) {
	conn := pipeDNSServer(t, func(q []byte) []byte {
		resp := dnsResponse("example.com", 60, [4]byte{1, 2, 3, 4})
		resp[0], resp[1] = 0xde, 0xad
		return resp
	})
	defer conn.Close()

	_, err := dnsStreamExchange(conn, dnsQuery("example.com"))
	if !errors.Is(err, errDNSMalformed) {
		t.Fatalf("err = %v, want errDNSMalformed", err)
	}
}

func TestDNSStreamExchange_zeroLength(t *testing.T) {
	c, s := net.Pipe()
	defer c.Close()
	go func() {
		defer s.Close()
		if _, err := readDNSFrame(s); err != nil {
			return
		}
		_, _ = s.Write([]byte{0, 0})
	}()

	_, err := dnsStreamExchange(c, dnsQuery("example.com"))
	if !errors.Is(err, errDNSMalformed) {
		t.Fatalf("err = %v, want errDNSMalformed", err)
	}
}

func TestDNSOverTCP_roundTripThroughClient(t *testing.T) {
	answer := dnsResponse("example.com", 60, [4]byte{9, 9, 9, 9})
	var dialed string
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		dialed = addr
		return pipeDNSServer(t, func(q []byte) []byte {
			resp := append([]byte(nil), answer...)
			copy(resp[:2], q[:2])
			return resp
		}), nil
	}}

	resp, err := dnsOverTCP(fc, "9.9.9.9:53", dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("dnsOverTCP: %v", err)
	}
	if dialed != "9.9.9.9:53" {
		t.Errorf("dialed %q, want 9.9.9.9:53", dialed)
	}
	if len(resp) != len(answer) {
		t.Errorf("response length = %d, want %d", len(resp), len(answer))
	}
}

func TestDNSOverTCPContext_cancelAborts(t *testing.T) {
	block := make(chan struct{})
	defer close(block)
	fc := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		c, s := net.Pipe()
		go func() {
			<-block
			s.Close()
		}()
		return c, nil
	}}
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	start := time.Now()
	_, err := dnsOverTCPContext(ctx, fc, "1.1.1.1:53", dnsQuery("example.com"))
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v, want context deadline", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Errorf("cancel took %v", time.Since(start))
	}
}
