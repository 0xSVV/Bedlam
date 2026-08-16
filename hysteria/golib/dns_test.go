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

func TestDNSStreamExchange_rejectsRuntFrames(t *testing.T) {
	// A frame too short to hold a DNS header must be an error, not a
	// silently accepted "answer" that suppresses failover.
	for _, payload := range [][]byte{{}, {0xff}, make([]byte, dnsHeaderLen-1)} {
		c, s := net.Pipe()
		go func(payload []byte) {
			defer s.Close()
			if _, err := readDNSFrame(s); err != nil {
				return
			}
			frame := make([]byte, 2+len(payload))
			frame[0] = byte(len(payload) >> 8)
			frame[1] = byte(len(payload))
			copy(frame[2:], payload)
			_, _ = s.Write(frame)
		}(payload)

		_, err := dnsStreamExchange(c, dnsQuery("example.com"))
		c.Close()
		if !errors.Is(err, errDNSMalformed) {
			t.Fatalf("%d-byte response: err = %v, want errDNSMalformed", len(payload), err)
		}
	}
}

func TestDNSUpstream_failsOverPastARuntResponse(t *testing.T) {
	runt := &fakeClient{tcp: func(addr string) (net.Conn, error) {
		c, s := net.Pipe()
		go func() {
			defer s.Close()
			if _, err := readDNSFrame(s); err != nil {
				return
			}
			_, _ = s.Write([]byte{0x00, 0x01, 0xff})
		}()
		return c, nil
	}}
	healthy := &stubResolver{name: "healthy", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	up := &dnsUpstream{
		resolvers: []dnsResolver{&tcpResolver{client: runt, server: "1.1.1.1:53"}, healthy},
		ident:     "tcp|runt,healthy",
	}

	ctx, cancel := context.WithTimeout(context.Background(), dnsQueryTimeout)
	defer cancel()
	resp, err := up.exchange(ctx, dnsQuery("example.com"))
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if resp[len(resp)-1] != 7 {
		t.Errorf("answer = %v, want the healthy server's", resp)
	}
	if healthy.calls.Load() != 1 {
		t.Error("the healthy server was never tried")
	}
	if up.preferred.Load() != 1 {
		t.Errorf("preferred = %d, want the healthy server", up.preferred.Load())
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
