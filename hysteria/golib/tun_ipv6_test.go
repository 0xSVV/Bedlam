package golib

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

func aaaaQuery(name string) []byte {
	q := dnsQuery(name)
	q[len(q)-3] = byte(dnsTypeAAAA)
	return q
}

func checkNoData(t *testing.T, query, resp []byte) {
	t.Helper()
	if !bytes.Equal(resp[:2], query[:2]) {
		t.Errorf("txid = %#x, want %#x", resp[:2], query[:2])
	}
	if resp[2]&0x80 == 0 {
		t.Error("QR flag not set")
	}
	if resp[3]&0x0f != 0 {
		t.Errorf("rcode = %d, want 0", resp[3]&0x0f)
	}
	if binary.BigEndian.Uint16(resp[4:6]) != 1 {
		t.Errorf("qdcount = %d, want 1", binary.BigEndian.Uint16(resp[4:6]))
	}
	for _, off := range []int{6, 8, 10} {
		if n := binary.BigEndian.Uint16(resp[off : off+2]); n != 0 {
			t.Errorf("record count at %d = %d, want 0", off, n)
		}
	}
	if !bytes.Equal(resp[dnsHeaderLen:], query[dnsHeaderLen:]) {
		t.Errorf("question = %v, want %v", resp[dnsHeaderLen:], query[dnsHeaderLen:])
	}
}

func TestServeDNSPackets_ipv6DisabledAnswersAAAAWithoutUpstream(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	h := testHandler(t, stub)
	h.ipv6Enabled = false
	pc := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)

	done := make(chan error, 1)
	go func() {
		done <- h.serveDNSPackets(context.Background(), pc, dest.String())
	}()

	q := aaaaQuery("example.com")
	pc.in <- fakePacket{q, dest}
	select {
	case p := <-pc.out:
		checkNoData(t, q, p.data)
		if p.addr.String() != dest.String() {
			t.Errorf("reply source = %s, want %s", p.addr, dest)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no AAAA answer")
	}
	if stub.calls.Load() != 0 {
		t.Errorf("resolver calls = %d, want 0", stub.calls.Load())
	}

	pc.in <- fakePacket{dnsQuery("example.com"), dest}
	select {
	case p := <-pc.out:
		if p.data[len(p.data)-1] != 7 {
			t.Errorf("A answer = %v", p.data)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no A answer")
	}
	if stub.calls.Load() != 1 {
		t.Errorf("resolver calls = %d, want 1", stub.calls.Load())
	}

	pc.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("serveDNSPackets did not return after close")
	}
}

func TestServeDNSStream_ipv6DisabledAnswersAAAAWithoutUpstream(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})}
	h := testHandler(t, stub)
	h.ipv6Enabled = false
	c, s := net.Pipe()
	done := make(chan error, 1)
	go func() { done <- h.serveDNSStream(context.Background(), s) }()

	q := aaaaQuery("example.com")
	if err := writeDNSFrame(c, q); err != nil {
		t.Fatal(err)
	}
	resp, err := readDNSFrame(c)
	if err != nil {
		t.Fatalf("read answer: %v", err)
	}
	checkNoData(t, q, resp)
	if stub.calls.Load() != 0 {
		t.Errorf("resolver calls = %d, want 0", stub.calls.Load())
	}

	c.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("serveDNSStream did not return after close")
	}
}

func TestServeDNSPackets_ipv6EnabledForwardsAAAA(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	h := testHandler(t, stub)
	pc := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)

	done := make(chan error, 1)
	go func() {
		done <- h.serveDNSPackets(context.Background(), pc, dest.String())
	}()

	pc.in <- fakePacket{aaaaQuery("example.com"), dest}
	select {
	case <-pc.out:
	case <-time.After(5 * time.Second):
		t.Fatal("no answer")
	}
	if stub.calls.Load() != 1 {
		t.Errorf("resolver calls = %d, want 1", stub.calls.Load())
	}

	pc.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("serveDNSPackets did not return after close")
	}
}

func TestBuildNoData_rejectsTruncatedQueries(t *testing.T) {
	if buildNoData(dnsQuery("example.com")[:10]) != nil {
		t.Error("a truncated header must not be answered")
	}
	q := dnsQuery("example.com")
	if buildNoData(q[:len(q)-2]) != nil {
		t.Error("a truncated question must not be answered")
	}
}
