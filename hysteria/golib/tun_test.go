package golib

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	singtun "github.com/sagernet/sing-tun"
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

func dohTunHandler(t *testing.T, d *dohServer) *tunHandler {
	t.Helper()
	r, err := newHTTPSResolver(d.client(), d.url(), &tls.Config{RootCAs: d.pool()})
	if err != nil {
		t.Fatal(err)
	}
	h := testHandler(t, r)
	t.Cleanup(h.dns.close)
	return h
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
		done <- h.serveDNSPackets(context.Background(), pc, dest.String())
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
	go h.serveDNSPackets(context.Background(), pc, dest.String())
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

func TestNewPacketConnectionEx_hardcodedResolverUsesTheUpstream(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	h := testHandler(t, stub)
	// A direct dial would mean plaintext DNS to the hard-coded server.
	h.client = &fakeClient{tcp: func(addr string) (net.Conn, error) {
		t.Errorf("dialed %q directly instead of using the configured upstream", addr)
		return nil, io.ErrUnexpectedEOF
	}}
	pc := newFakePacketConn()
	dest := M.SocksaddrFrom(netip.MustParseAddr("8.8.8.8"), 53)
	go h.NewPacketConnectionEx(context.Background(), pc, M.Socksaddr{}, dest, nil)
	defer pc.Close()

	pc.in <- fakePacket{dnsQuery("example.com"), dest}
	select {
	case p := <-pc.out:
		if p.data[len(p.data)-1] != 7 {
			t.Errorf("answer = %v, want the upstream's", p.data)
		}
		if p.addr.String() != dest.String() {
			t.Errorf("reply source = %s, want the address the app queried", p.addr)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no answer")
	}
	if stub.calls.Load() != 1 {
		t.Errorf("upstream calls = %d, want 1", stub.calls.Load())
	}
}

func TestNewConnectionEx_hardcodedResolverUsesTheUpstream(t *testing.T) {
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	h := testHandler(t, stub)
	h.client = &fakeClient{tcp: func(addr string) (net.Conn, error) {
		t.Errorf("relayed TCP:53 to %q instead of using the configured upstream", addr)
		return nil, io.ErrUnexpectedEOF
	}}

	c, s := net.Pipe()
	go h.NewConnectionEx(context.Background(), s, M.Socksaddr{}, M.SocksaddrFrom(netip.MustParseAddr("8.8.8.8"), 53), nil)
	if err := writeDNSFrame(c, dnsQuery("example.com")); err != nil {
		t.Fatal(err)
	}
	resp, err := readDNSFrame(c)
	if err != nil {
		t.Fatalf("TCP:53 to a hard-coded server should be served: %v", err)
	}
	if resp[len(resp)-1] != 7 {
		t.Errorf("answer = %v", resp)
	}
	c.Close()
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

func TestNewConnectionEx_resolverPorts(t *testing.T) {
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})})

	c, s := net.Pipe()
	errCh := make(chan error, 1)
	go h.NewConnectionEx(context.Background(), s, M.Socksaddr{}, M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 853), func(err error) {
		errCh <- err
	})
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("port 853 on the resolver address must be refused")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("NewConnectionEx hung")
	}
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Error("refused connection should be closed")
	}

	c2, s2 := net.Pipe()
	go h.NewConnectionEx(context.Background(), s2, M.Socksaddr{}, M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53), nil)
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

func TestServeDNSPackets_cachedAnswerSkipsSaturatedSlots(t *testing.T) {
	release := make(chan struct{})
	defer close(release)

	var served atomic.Int32
	stub := &stubResolver{name: "stub", reply: func(q []byte) ([]byte, error) {
		if served.Add(1) > 1 {
			<-release
		}
		return dnsResponseFor(q, 60, [4]byte{9, 9, 9, 9}), nil
	}}
	h := testHandler(t, stub)
	pc := newFakePacketConn()
	defer pc.Close()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)
	go h.serveDNSPackets(context.Background(), pc, dest.String())

	pc.in <- fakePacket{dnsQuery("cached.example"), dest}
	select {
	case <-pc.out:
	case <-time.After(5 * time.Second):
		t.Fatal("priming query was not answered")
	}

	for i := 0; i < maxConcurrentDNS; i++ {
		pc.in <- fakePacket{dnsQuery(fmt.Sprintf("miss%d.example", i)), dest}
	}
	deadline := time.Now().Add(5 * time.Second)
	for stub.calls.Load() < int32(maxConcurrentDNS)+1 {
		if time.Now().After(deadline) {
			t.Fatalf("only %d of %d slots taken", stub.calls.Load()-1, maxConcurrentDNS)
		}
		time.Sleep(10 * time.Millisecond)
	}

	pc.in <- fakePacket{dnsQuery("cached.example"), dest}
	select {
	case p := <-pc.out:
		if p.data[len(p.data)-1] != 9 {
			t.Errorf("answer = %v", p.data)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cached answer waited on the saturated in-flight slots")
	}
	if got := stub.calls.Load(); got != int32(maxConcurrentDNS)+1 {
		t.Errorf("resolver calls = %d, want %d (cache hit must not reach the resolver)", got, maxConcurrentDNS+1)
	}
}

func TestTunHandler_dohBodyIsTheQueryFromEitherIngress(t *testing.T) {
	d := newDoHServer(t, [4]byte{7, 7, 7, 7}, http.StatusOK)
	h := dohTunHandler(t, d)
	dest := M.SocksaddrFrom(netip.MustParseAddr("203.0.113.53"), 53)
	zeroID := func(q []byte) []byte {
		out := append([]byte(nil), q...)
		out[0], out[1] = 0, 0
		return out
	}

	pc := newFakePacketConn()
	defer pc.Close()
	go h.serveDNSPackets(context.Background(), pc, dest.String())
	udpQueries := [][]byte{withPadding(dnsQuery("udp.example"), 700), dnsQuery("small.udp.example")}
	for i, query := range udpQueries {
		binary.BigEndian.PutUint16(query[:2], uint16(0x4240+i))
		pc.in <- fakePacket{append([]byte(nil), query...), dest}
		select {
		case p := <-pc.out:
			if p.data[len(p.data)-1] != 7 {
				t.Errorf("UDP answer %d = %v", i, p.data)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("no UDP answer to query %d", i)
		}
		if got := d.lastBody(); !bytes.Equal(got, zeroID(query)) {
			t.Errorf("UDP ingress posted %d bytes for query %d, want the %d-byte datagram with a zero ID", len(got), i, len(query))
		}
	}

	c, s := net.Pipe()
	defer c.Close()
	go h.serveDNSStream(context.Background(), s)
	tcpQueries := [][]byte{withPadding(dnsQuery("tcp.example"), 700), dnsQuery("small.tcp.example")}
	for i, query := range tcpQueries {
		binary.BigEndian.PutUint16(query[:2], uint16(0x4340+i))
		if err := writeDNSFrame(c, query); err != nil {
			t.Fatal(err)
		}
		resp, err := readDNSFrame(c)
		if err != nil {
			t.Fatalf("TCP answer to query %d: %v", i, err)
		}
		if resp[len(resp)-1] != 7 {
			t.Errorf("TCP answer %d = %v", i, resp)
		}
		if got := d.lastBody(); !bytes.Equal(got, zeroID(query)) {
			t.Errorf("TCP ingress posted %d bytes for query %d, want the %d-byte frame payload with a zero ID", len(got), i, len(query))
		}
	}
	if want := int32(len(udpQueries) + len(tcpQueries)); d.requests.Load() != want {
		t.Errorf("server saw %d requests, want %d", d.requests.Load(), want)
	}
}

func TestServeDNSPackets_nonDNSPayloadNeverReachesTheUpstream(t *testing.T) {
	d := newDoHServer(t, [4]byte{7, 7, 7, 7}, http.StatusOK)
	d.maxBody.Store(dohFixtureQueryLimit)
	h := dohTunHandler(t, d)
	pc := newFakePacketConn()
	defer pc.Close()
	dest := M.SocksaddrFrom(netip.MustParseAddr("203.0.113.53"), 53)
	go h.serveDNSPackets(context.Background(), pc, dest.String())

	for _, size := range []int{148, 1400} {
		pc.in <- fakePacket{nonDNSPayload(size), dest}
		select {
		case p := <-pc.out:
			if len(p.data) != size || p.data[3]&0x0f != 2 {
				t.Errorf("%d-byte payload: reply is %d bytes with rcode %d, want a SERVFAIL of the same size", size, len(p.data), p.data[3]&0x0f)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("no reply to a %d-byte non-DNS payload", size)
		}
	}
	if n := d.requests.Load(); n != 0 {
		t.Errorf("the DoH server saw %d requests for non-DNS payloads", n)
	}

	pc.in <- fakePacket{dnsQuery("example.com"), dest}
	select {
	case p := <-pc.out:
		if p.data[len(p.data)-1] != 7 {
			t.Errorf("answer = %v", p.data)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no answer to a real query")
	}
	if d.requests.Load() != 1 {
		t.Errorf("the DoH server saw %d requests in total, want 1", d.requests.Load())
	}
}

func TestServeDNSStream_nonDNSFrameNeverReachesTheUpstream(t *testing.T) {
	d := newDoHServer(t, [4]byte{7, 7, 7, 7}, http.StatusOK)
	d.maxBody.Store(dohFixtureQueryLimit)
	h := dohTunHandler(t, d)
	c, s := net.Pipe()
	defer c.Close()
	go h.serveDNSStream(context.Background(), s)

	if err := writeDNSFrame(c, nonDNSPayload(1400)); err != nil {
		t.Fatal(err)
	}
	resp, err := readDNSFrame(c)
	if err != nil {
		t.Fatalf("read SERVFAIL: %v", err)
	}
	if len(resp) != 1400 || resp[3]&0x0f != 2 {
		t.Errorf("reply is %d bytes with rcode %d, want a 1400-byte SERVFAIL", len(resp), resp[3]&0x0f)
	}
	if n := d.requests.Load(); n != 0 {
		t.Errorf("the DoH server saw %d requests for a non-DNS frame", n)
	}

	if err := writeDNSFrame(c, dnsQuery("example.com")); err != nil {
		t.Fatal(err)
	}
	resp, err = readDNSFrame(c)
	if err != nil {
		t.Fatalf("the stream must stay open after a refused frame: %v", err)
	}
	if resp[len(resp)-1] != 7 {
		t.Errorf("answer = %v", resp)
	}
}

type captureLog struct {
	mu    sync.Mutex
	lines []string
}

func (c *captureLog) OnLog(level, source, message string) {
	c.mu.Lock()
	c.lines = append(c.lines, level+" "+source+" "+message)
	c.mu.Unlock()
}

func (c *captureLog) linesMentioning(s string) []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	var out []string
	for _, line := range c.lines {
		if strings.Contains(line, s) {
			out = append(out, line)
		}
	}
	return out
}

func captureLogs(t *testing.T) *captureLog {
	t.Helper()
	c := &captureLog{}
	SetLogHandler(c)
	t.Cleanup(func() { SetLogHandler(nil) })
	return c
}

var upstreamIDSeq atomic.Int64

func uniqueUpstreamID(t *testing.T, transport string) string {
	return transport + "|" + t.Name() + "-" + strconv.FormatInt(upstreamIDSeq.Add(1), 10)
}

func TestServeDNSPackets_nonDNSPayloadLogsItsSizeOnly(t *testing.T) {
	logs := captureLogs(t)
	stub := &stubResolver{name: "stub", reply: echoAnswer([4]byte{7, 7, 7, 7})}
	h := testHandler(t, stub)
	h.dns.ident = uniqueUpstreamID(t, "udp")
	pc := newFakePacketConn()
	defer pc.Close()
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)
	go h.serveDNSPackets(context.Background(), pc, dest.String())

	pc.in <- fakePacket{nonDNSPayload(1400), dest}
	select {
	case <-pc.out:
	case <-time.After(5 * time.Second):
		t.Fatal("no SERVFAIL")
	}
	want := "INFO dns Refused a packet on port 53 that is not a single DNS query (1400 bytes)"
	if got := logs.linesMentioning("port 53"); len(got) != 1 || got[0] != want {
		t.Errorf("logged %q, want exactly %q", got, want)
	}
	if got := logs.linesMentioning(h.dns.ident); len(got) != 0 {
		t.Errorf("logged %q, want no upstream error for a refused packet", got)
	}
	if stub.calls.Load() != 0 {
		t.Errorf("resolver calls = %d", stub.calls.Load())
	}
}

func TestLogDNSError_invalidPayloadsDoNotMuteUpstreamErrors(t *testing.T) {
	logs := captureLogs(t)
	id := uniqueUpstreamID(t, "tls")
	payload := nonDNSPayload(1400)
	invalid := fmt.Errorf("%w (1400 bytes)", errDNSQueryInvalid)
	upstream := errors.New("read response length: i/o timeout")

	logDNSError(id, payload, invalid)
	logDNSError(id, payload, upstream)
	logDNSError(id, payload, invalid)
	logDNSError(id, payload, upstream)

	refused := logs.linesMentioning("port 53")
	wantRefused := "INFO dns Refused a packet on port 53 that is not a single DNS query (1400 bytes)"
	if len(refused) != 1 || refused[0] != wantRefused {
		t.Errorf("logged %q, want exactly %q within the rate limit", refused, wantRefused)
	}
	errs := logs.linesMentioning(id)
	wantErr := "WARN dns DNS error: " + id + ": " + upstream.Error()
	if len(errs) != 1 || errs[0] != wantErr {
		t.Errorf("logged %q, want exactly %q within the rate limit", errs, wantErr)
	}
}

func TestTunHandler_truncatedAnswerIsRetriedOverTCP(t *testing.T) {
	var mu sync.Mutex
	var seen [][]byte
	stub := &stubResolver{name: "stub", reply: func(q []byte) ([]byte, error) {
		mu.Lock()
		seen = append(seen, append([]byte(nil), q...))
		first := len(seen) == 1
		mu.Unlock()
		resp := dnsResponseFor(q, 60, [4]byte{8, 8, 8, 8})
		resp[10], resp[11] = 0, 0
		if first {
			resp[2] |= 0x02
		}
		return resp, nil
	}}
	h := testHandler(t, stub)
	query := withEDNS(dnsQuery("large.example"), 1232, true)
	dest := M.SocksaddrFrom(netip.MustParseAddr("172.19.0.2"), 53)

	pc := newFakePacketConn()
	defer pc.Close()
	go h.serveDNSPackets(context.Background(), pc, dest.String())
	pc.in <- fakePacket{append([]byte(nil), query...), dest}
	select {
	case p := <-pc.out:
		if p.data[2]&0x02 == 0 {
			t.Fatal("the UDP answer should carry TC")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no UDP answer")
	}

	c, s := net.Pipe()
	defer c.Close()
	go h.serveDNSStream(context.Background(), s)
	if err := writeDNSFrame(c, query); err != nil {
		t.Fatal(err)
	}
	resp, err := readDNSFrame(c)
	if err != nil {
		t.Fatalf("TCP retry: %v", err)
	}
	if resp[2]&0x02 != 0 || resp[len(resp)-1] != 8 {
		t.Errorf("TCP retry answer = %v", resp)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 2 {
		t.Fatalf("resolver saw %d queries, want 2 (a truncated answer is not cached)", len(seen))
	}
	for i, q := range seen {
		if !bytes.Equal(q, query) {
			t.Errorf("attempt %d reached the resolver altered", i)
		}
	}
}

func TestTunStackOptions_keepPerFlowUDPSessions(t *testing.T) {
	inet4 := netip.MustParsePrefix("172.19.0.1/30")
	inet6 := netip.MustParsePrefix("fdfe:dcba:9876::1/126")
	tunOpts := tunOptions(42, 1500, inet4, inet6)
	if tunOpts.FileDescriptor != 42 || tunOpts.MTU != 1500 {
		t.Errorf("fd = %d, mtu = %d, want 42 and 1500", tunOpts.FileDescriptor, tunOpts.MTU)
	}
	if len(tunOpts.Inet4Address) != 1 || tunOpts.Inet4Address[0] != inet4 ||
		len(tunOpts.Inet6Address) != 1 || tunOpts.Inet6Address[0] != inet6 {
		t.Errorf("addresses = %v and %v, want %s and %s", tunOpts.Inet4Address, tunOpts.Inet6Address, inet4, inet6)
	}
	if tunOpts.DNSMode != singtun.DNSModeDisabled {
		t.Errorf("DNS mode = %q, want %q", tunOpts.DNSMode, singtun.DNSModeDisabled)
	}

	h := &tunHandler{}
	opts := tunStackOptions(context.Background(), nil, tunOpts, h)
	if opts.UDPTimeout != 5*time.Minute {
		t.Errorf("UDP timeout = %s, want 5m0s", opts.UDPTimeout)
	}
	if opts.UDPMapping != singtun.NATMappingAddressAndPortDependent {
		t.Errorf("UDP mapping = %d, want one session per source and destination", opts.UDPMapping)
	}
	if opts.UDPNATMax != 16384 {
		t.Errorf("UDP session limit = %d, want a fixed 16384 rather than one sized from the process RSS", opts.UDPNATMax)
	}
	if opts.Handler != singtun.Handler(h) || opts.TunOptions.FileDescriptor != 42 {
		t.Error("stack options must carry the handler and the TUN options")
	}
}

func TestTunHandler_judgeFlowLeavesEveryFlowToTheHandlers(t *testing.T) {
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})})
	h.ipv6Enabled = false
	source := netip.MustParseAddrPort("172.19.0.1:40000")
	cases := []struct {
		network     uint8
		destination string
	}{
		{6, "203.0.113.7:443"},
		{17, "172.19.0.2:53"},
		{6, "172.19.0.2:853"},
		{17, "[2001:db8::1]:443"},
		{1, "203.0.113.7:0"},
	}
	for _, c := range cases {
		verdict := h.JudgeFlow(c.network, source, netip.MustParseAddrPort(c.destination), nil)
		if verdict.Action != singtun.ActionAccept {
			t.Errorf("protocol %d to %s: action = %d, want accept", c.network, c.destination, verdict.Action)
		}
	}
}

type handshakeConn struct {
	net.Conn
	handshakes atomic.Int32
}

func (c *handshakeConn) HandshakeSuccess() error {
	c.handshakes.Add(1)
	return nil
}

func TestNewConnectionEx_acceptsBeforeDialing(t *testing.T) {
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})})
	c, s := net.Pipe()
	defer c.Close()
	conn := &handshakeConn{Conn: s}
	dialErr := errors.New("dial refused")
	h.client = &fakeClient{tcp: func(addr string) (net.Conn, error) {
		if n := conn.handshakes.Load(); n != 1 {
			t.Errorf("dialed %s after %d handshakes, want the app's connection accepted first", addr, n)
		}
		return nil, dialErr
	}}

	closed := make(chan error, 1)
	go h.NewConnectionEx(context.Background(), conn, M.Socksaddr{}, M.SocksaddrFrom(netip.MustParseAddr("203.0.113.7"), 443), func(err error) {
		closed <- err
	})
	select {
	case err := <-closed:
		if !errors.Is(err, dialErr) {
			t.Errorf("onClose got %v, want the dial error", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("NewConnectionEx hung")
	}
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Error("the app's connection should be closed after a failed dial")
	}
}

type timeoutPacketConn struct {
	*fakePacketConn
	mu       sync.Mutex
	timeouts []time.Duration
}

func (c *timeoutPacketConn) Timeout() time.Duration { return udpSessionTimeout }

func (c *timeoutPacketConn) SetTimeout(timeout time.Duration) bool {
	c.mu.Lock()
	c.timeouts = append(c.timeouts, timeout)
	c.mu.Unlock()
	return true
}

func (c *timeoutPacketConn) recorded() []time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]time.Duration(nil), c.timeouts...)
}

func udpRelayHandler(t *testing.T) (*tunHandler, *fakeUDPConn) {
	t.Helper()
	h := testHandler(t, &stubResolver{name: "stub", reply: echoAnswer([4]byte{5, 5, 5, 5})})
	uc := newFakeUDPConn(nil)
	h.client = &fakeClient{udp: func() (client.HyUDPConn, error) { return uc, nil }}
	return h, uc
}

func TestNewPacketConnectionEx_repliesComeFromTheAppsDestination(t *testing.T) {
	h, uc := udpRelayHandler(t)
	pc := newFakePacketConn()
	defer pc.Close()
	dest := M.SocksaddrFrom(netip.MustParseAddr("203.0.113.7"), 3478)
	go h.NewPacketConnectionEx(context.Background(), pc, M.Socksaddr{}, dest, nil)

	for _, from := range []string{"203.0.113.7:3478", "198.51.100.9:4444", "[::ffff:203.0.113.7]:3478", ""} {
		uc.recv <- udpMsg{[]byte("pong"), from}
		select {
		case p := <-pc.out:
			if p.addr != dest {
				t.Errorf("reply the server reported from %q reached the app from %s, want %s", from, p.addr, dest)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("the reply reported from %q never reached the app", from)
		}
	}
}

func TestNewPacketConnectionEx_repliesKeepTheSessionAlive(t *testing.T) {
	h, uc := udpRelayHandler(t)
	pc := &timeoutPacketConn{fakePacketConn: newFakePacketConn()}
	defer pc.Close()
	dest := M.SocksaddrFrom(netip.MustParseAddr("203.0.113.7"), 3478)
	go h.NewPacketConnectionEx(context.Background(), pc, M.Socksaddr{}, dest, nil)

	for i := 0; i < 3; i++ {
		uc.recv <- udpMsg{[]byte("pong"), dest.String()}
		select {
		case <-pc.out:
		case <-time.After(5 * time.Second):
			t.Fatalf("reply %d never reached the app", i)
		}
	}
	timeouts := pc.recorded()
	if len(timeouts) == 0 || timeouts[0] != udpSessionTimeout {
		t.Errorf("SetTimeout calls = %v, want replies to renew the %s idle timeout", timeouts, udpSessionTimeout)
	}
}

func TestUDPIdleRefresh_renewsAtMostOncePerInterval(t *testing.T) {
	pc := &timeoutPacketConn{fakePacketConn: newFakePacketConn()}
	r := newUDPIdleRefresh(pc)
	start := time.Now()
	r.touch(start)
	r.touch(start.Add(udpIdleRefreshInterval - time.Millisecond))
	r.touch(start.Add(udpIdleRefreshInterval))
	if got := pc.recorded(); len(got) != 2 {
		t.Errorf("SetTimeout calls = %v, want 2 over one refresh interval", got)
	}

	newUDPIdleRefresh(newFakePacketConn()).touch(start)
}
