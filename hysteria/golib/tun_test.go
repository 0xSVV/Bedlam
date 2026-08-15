package golib

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
)

type fakePacket struct {
	data []byte
	addr M.Socksaddr
}

type fakePacketConn struct {
	in        chan fakePacket
	out       chan fakePacket
	closed    chan struct{}
	closeOnce sync.Once
}

func newFakePacketConn() *fakePacketConn {
	return &fakePacketConn{
		in:     make(chan fakePacket, 16),
		out:    make(chan fakePacket, 16),
		closed: make(chan struct{}),
	}
}

func (c *fakePacketConn) ReadPacket(buffer *buf.Buffer) (M.Socksaddr, error) {
	select {
	case p := <-c.in:
		_, _ = buffer.Write(p.data)
		return p.addr, nil
	case <-c.closed:
		return M.Socksaddr{}, io.EOF
	}
}

func (c *fakePacketConn) WritePacket(buffer *buf.Buffer, destination M.Socksaddr) error {
	data := append([]byte(nil), buffer.Bytes()...)
	buffer.Release()
	select {
	case c.out <- fakePacket{data, destination}:
		return nil
	case <-c.closed:
		return net.ErrClosed
	}
}

func (c *fakePacketConn) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	return nil
}

func (c *fakePacketConn) LocalAddr() net.Addr                { return nil }
func (c *fakePacketConn) SetDeadline(time.Time) error        { return nil }
func (c *fakePacketConn) SetReadDeadline(time.Time) error    { return nil }
func (c *fakePacketConn) SetWriteDeadline(time.Time) error   { return nil }

func testHandler(t *testing.T, resolver dnsResolver) *tunHandler {
	t.Helper()
	up := &dnsUpstream{
		resolvers: []dnsResolver{resolver},
		ident:     "stub",
		listen:    []netip.Addr{netip.MustParseAddr("172.19.0.2"), netip.MustParseAddr("fdfe:dcba:9876::2")},
	}
	return &tunHandler{
		session:     &Session{dnsCache: newDNSCache()},
		client:      &fakeClient{},
		ipv6Enabled: true,
		dns:         up,
	}
}

func TestTunHandler_isResolverAddr(t *testing.T) {
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{1, 1, 1, 1})})
	cases := []struct {
		addr string
		want bool
	}{
		{"172.19.0.2", true},
		{"::ffff:172.19.0.2", true},
		{"fdfe:dcba:9876::2", true},
		{"172.19.0.1", false},
		{"1.1.1.1", false},
	}
	for _, c := range cases {
		dest := M.SocksaddrFrom(netip.MustParseAddr(c.addr), 53)
		if got := h.isResolverAddr(dest); got != c.want {
			t.Errorf("isResolverAddr(%s) = %v, want %v", c.addr, got, c.want)
		}
	}
	if (&tunHandler{}).isResolverAddr(M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)) {
		t.Error("handler without upstream must not claim the resolver address")
	}
}

func TestServeDNSPackets_answersOnTunResolver(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	h := testHandler(t, stub)
	pc := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)

	done := make(chan error, 1)
	go func() {
		done <- h.serveDNSPackets(context.Background(), pc, dest.String(), func(string) dnsResolver { return h.dns })
	}()

	q := dnsQuery("example.com")
	binary.BigEndian.PutUint16(q[:2], 0x7777)
	pc.in <- fakePacket{q, dest}

	select {
	case p := <-pc.out:
		if binary.BigEndian.Uint16(p.data[:2]) != 0x7777 {
			t.Errorf("txid = %#x", binary.BigEndian.Uint16(p.data[:2]))
		}
		if p.data[len(p.data)-1] != 7 {
			t.Errorf("answer = %v", p.data)
		}
		if p.addr.String() != dest.String() {
			t.Errorf("reply source = %s, want %s", p.addr, dest)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no answer")
	}
	if stub.calls.Load() != 1 {
		t.Errorf("resolver calls = %d", stub.calls.Load())
	}

	pc.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("serveDNSPackets did not return after close")
	}
}

func TestServeDNSPackets_servfailOnError(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: func([]byte) ([]byte, error) { return nil, io.ErrUnexpectedEOF }}
	h := testHandler(t, stub)
	pc := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)
	go h.serveDNSPackets(context.Background(), pc, dest.String(), func(string) dnsResolver { return h.dns })
	defer pc.Close()

	pc.in <- fakePacket{dnsQuery("example.com"), dest}
	select {
	case p := <-pc.out:
		if p.data[3]&0x0f != 2 {
			t.Errorf("rcode = %d, want SERVFAIL", p.data[3]&0x0f)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no SERVFAIL")
	}
}

func TestHandleDNSOverTCP_usesPacketDestination(t *testing.T) {
	answer := dnsResponse("example.com", 60, [4]byte{8, 8, 8, 8})
	var dialed string
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{1, 1, 1, 1})})
	h.client = &fakeClient{tcp: func(addr string) (net.Conn, error) {
		dialed = addr
		return pipeDNSServer(t, func(q []byte) []byte {
			resp := append([]byte(nil), answer...)
			copy(resp[:2], q[:2])
			return resp
		}), nil
	}}
	pc := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("8.8.8.8"), 53)
	go h.handleDNSOverTCP(context.Background(), pc, dest.String())
	defer pc.Close()

	pc.in <- fakePacket{dnsQuery("example.com"), dest}
	select {
	case p := <-pc.out:
		if p.data[len(p.data)-1] != 8 {
			t.Errorf("answer = %v", p.data)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no answer")
	}
	if dialed != "8.8.8.8:53" {
		t.Errorf("legacy path dialed %q, want the packet destination", dialed)
	}
}

func TestServeDNSStream_framedRoundTrip(t *testing.T) {
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})})
	c, s := net.Pipe()
	done := make(chan error, 1)
	go func() { done <- h.serveDNSStream(context.Background(), s) }()

	q := dnsQuery("example.com")
	if err := writeDNSFrame(c, q); err != nil {
		t.Fatal(err)
	}
	resp, err := readDNSFrame(c)
	if err != nil {
		t.Fatalf("read answer: %v", err)
	}
	if resp[len(resp)-1] != 5 {
		t.Errorf("answer = %v", resp)
	}
	c.Close()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("serveDNSStream did not return after close")
	}
}

func TestNewConnection_resolverPorts(t *testing.T) {
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})})

	c, s := net.Pipe()
	errCh := make(chan error, 1)
	go func() {
		errCh <- h.NewConnection(context.Background(), s, M.Metadata{
			Destination: M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 853),
		})
	}()
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("port 853 on the resolver address must be refused")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("NewConnection hung")
	}
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Error("refused connection should be closed")
	}

	c2, s2 := net.Pipe()
	go func() {
		_ = h.NewConnection(context.Background(), s2, M.Metadata{
			Destination: M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53),
		})
	}()
	if err := writeDNSFrame(c2, dnsQuery("example.com")); err != nil {
		t.Fatal(err)
	}
	resp, err := readDNSFrame(c2)
	if err != nil {
		t.Fatalf("TCP:53 on the resolver should be served: %v", err)
	}
	if resp[len(resp)-1] != 5 {
		t.Errorf("answer = %v", resp)
	}
	c2.Close()
}
