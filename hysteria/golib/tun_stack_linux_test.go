//go:build with_gvisor

package golib

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"runtime"
	"runtime/pprof"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/gvisor/pkg/tcpip/link/fdbased"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv4"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv6"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/udp"
	"golang.org/x/sys/unix"
)

const (
	stackEchoAddr    = "203.0.113.7:443"
	stackEchoAddr6   = "[2001:db8::1]:443"
	stackStalledAddr = "203.0.113.8:443"
	stackUpstreamDNS = "1.1.1.1:53"
)

type tunStackRig struct {
	t        *testing.T
	sess     *Session
	app      *stack.Stack
	appFD    int
	tunFD    int
	udpConns chan *fakeUDPConn
	dials    atomic.Int32
	stalled  chan net.Conn
	baseline map[string]bool
}

func newTunStackRig(t *testing.T, ipv6Enabled bool) *tunStackRig {
	t.Helper()
	echo, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { echo.Close() })
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}()
		}
	}()

	r := &tunStackRig{
		t:        t,
		udpConns: make(chan *fakeUDPConn, 64),
		stalled:  make(chan net.Conn, 64),
		baseline: tunStackGoroutines(),
	}
	fc := &fakeClient{
		tcp: func(addr string) (net.Conn, error) {
			r.dials.Add(1)
			switch addr {
			case stackUpstreamDNS:
				return pipeDNSServer(t, func(q []byte) []byte {
					resp, _ := echoAnswer([4]byte{9, 9, 9, 9})(q)
					return resp
				}), nil
			case stackEchoAddr, stackEchoAddr6:
				return net.Dial("tcp", echo.Addr().String())
			case stackStalledAddr:
				a, b := net.Pipe()
				r.stalled <- b
				return a, nil
			default:
				return nil, fmt.Errorf("fake dial refused: %s", addr)
			}
		},
		udp: func() (client.HyUDPConn, error) {
			uc := newFakeUDPConn(nil)
			r.udpConns <- uc
			return uc, nil
		},
	}
	r.sess = &Session{
		client:      &reconnectClient{inner: fc},
		activeConns: map[net.PacketConn]struct{}{},
		dnsCache:    newDNSCache(),
	}
	r.start(ipv6Enabled)
	return r
}

func (r *tunStackRig) start(ipv6Enabled bool) {
	t := r.t
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	r.tunFD, r.appFD = fds[0], fds[1]
	dns := `{"transport":"tcp","servers":["` + stackUpstreamDNS + `"],"listen":["172.19.0.2","fdfe:dcba:9876::2"]}`
	if err := r.sess.StartTUN(int32(r.tunFD), 1500, "172.19.0.1/30", "fdfe:dcba:9876::1/126", ipv6Enabled, dns); err != nil {
		unix.Close(r.tunFD)
		unix.Close(r.appFD)
		t.Fatal(err)
	}

	app := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{r.appFD}, MTU: 1500})
	if err != nil {
		t.Fatal(err)
	}
	if terr := app.CreateNIC(1, ep); terr != nil {
		t.Fatal(terr)
	}
	app.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{172, 19, 0, 1}).WithPrefix(),
	}, stack.AddressProperties{})
	app.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom16(netip.MustParseAddr("fdfe:dcba:9876::1").As16()).WithPrefix(),
	}, stack.AddressProperties{})
	app.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: 1},
		{Destination: header.IPv6EmptySubnet, NIC: 1},
	})
	sndOpt := tcpip.TCPSendBufferSizeRangeOption{Min: 4096, Default: 64 << 10, Max: 64 << 10}
	app.SetTransportProtocolOption(tcp.ProtocolNumber, &sndOpt)
	r.app = app
}

func (r *tunStackRig) stop() {
	r.t.Helper()
	start := time.Now()
	if err := r.sess.StopTUN(); err != nil {
		r.t.Errorf("StopTUN after %s: %v", time.Since(start), err)
	}
	if _, err := unix.FcntlInt(uintptr(r.tunFD), unix.F_GETFD, 0); !errors.Is(err, unix.EBADF) {
		r.t.Errorf("TUN fd after StopTUN: fcntl err = %v, want EBADF", err)
	}
	if r.app != nil {
		r.app.Close()
		r.app.Wait()
		r.app = nil
	}
	unix.Close(r.appFD)
	r.waitForStackGoroutines()
}

func (r *tunStackRig) waitForStackGoroutines() {
	r.t.Helper()
	deadline := time.Now().Add(6 * time.Second)
	for {
		var leaked []string
		for g := range tunStackGoroutines() {
			if !r.baseline[g] {
				leaked = append(leaked, g)
			}
		}
		if len(leaked) == 0 {
			return
		}
		if time.Now().After(deadline) {
			r.t.Errorf("goroutines left after StopTUN:\n%s", strings.Join(leaked, "\n\n"))
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func tunStackGoroutines() map[string]bool {
	var b bytes.Buffer
	_ = pprof.Lookup("goroutine").WriteTo(&b, 1)
	stacks := map[string]bool{}
	for _, block := range strings.Split(b.String(), "\n\n") {
		var lines []string
		for _, line := range strings.Split(block, "\n") {
			if strings.HasPrefix(line, "#") {
				lines = append(lines, line)
			}
		}
		frames := strings.Join(lines, "\n")
		if frames == "" || strings.Contains(frames, "tun_stack_linux_test.go") {
			continue
		}
		if strings.Contains(frames, "sagernet/sing-tun.") ||
			strings.Contains(frames, "sagernet/sing/") ||
			strings.Contains(frames, "bedlam/golib.(*tunHandler)") ||
			strings.Contains(frames, "bedlam/golib.(*dnsUpstream)") {
			stacks[frames] = true
		}
	}
	return stacks
}

func stackFullAddr(s string) (tcpip.FullAddress, tcpip.NetworkProtocolNumber) {
	ap := netip.MustParseAddrPort(s)
	if ap.Addr().Is4() {
		return tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(ap.Addr().As4()), Port: ap.Port()}, ipv4.ProtocolNumber
	}
	return tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom16(ap.Addr().As16()), Port: ap.Port()}, ipv6.ProtocolNumber
}

func (r *tunStackRig) dialTCP(s string) (*gonet.TCPConn, error) {
	fa, proto := stackFullAddr(s)
	return gonet.DialTCP(r.app, fa, proto)
}

func (r *tunStackRig) nextUDPSession() *fakeUDPConn {
	r.t.Helper()
	select {
	case uc := <-r.udpConns:
		return uc
	case <-time.After(5 * time.Second):
		r.t.Fatal("no UDP session opened")
		return nil
	}
}

func TestTunStack_relaysTCPOverIPv4AndIPv6(t *testing.T) {
	r := newTunStackRig(t, true)
	msg := bytes.Repeat([]byte("0123456789abcdef"), 40000)
	for _, dst := range []string{stackEchoAddr, stackEchoAddr6} {
		c, err := r.dialTCP(dst)
		if err != nil {
			t.Fatalf("%s: dial: %v", dst, err)
		}
		writeErr := make(chan error, 1)
		go func() {
			_, err := c.Write(msg)
			writeErr <- err
		}()
		_ = c.SetDeadline(time.Now().Add(10 * time.Second))
		got := make([]byte, len(msg))
		n, err := io.ReadFull(c, got)
		if err != nil || !bytes.Equal(got, msg) {
			t.Errorf("%s: echoed %d of %d bytes, err = %v", dst, n, len(msg), err)
		}
		if err := <-writeErr; err != nil {
			t.Errorf("%s: write: %v", dst, err)
		}
		c.Close()
	}
	if tx, rx := r.sess.txBytes.Load(), r.sess.rxBytes.Load(); tx < int64(len(msg)) || rx < int64(len(msg)) {
		t.Errorf("counters tx = %d, rx = %d, want at least %d each", tx, rx, len(msg))
	}
	r.stop()
}

func TestTunStack_refusedTCPFlowsEndInsteadOfHanging(t *testing.T) {
	r := newTunStackRig(t, false)
	for _, dst := range []string{"203.0.113.9:443", stackEchoAddr6, "172.19.0.2:853"} {
		c, err := r.dialTCP(dst)
		if err != nil {
			continue
		}
		_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
		n, err := c.Read(make([]byte, 16))
		var netErr net.Error
		if n != 0 || err == nil || (errors.As(err, &netErr) && netErr.Timeout()) {
			t.Errorf("%s: read n = %d, err = %v, want the connection to end", dst, n, err)
		}
		c.Close()
	}
	r.stop()
}

func TestTunStack_udpRepliesComeFromTheAppsDestination(t *testing.T) {
	r := newTunStackRig(t, true)
	for _, tc := range []struct{ local, remote, from string }{
		{"172.19.0.1:40000", "203.0.113.7:3478", "198.51.100.9:4444"},
		{"[fdfe:dcba:9876::1]:40001", "[2001:db8::7]:3478", "[2001:db8::99]:1"},
	} {
		la, proto := stackFullAddr(tc.local)
		pc, err := gonet.DialUDP(r.app, &la, nil, proto)
		if err != nil {
			t.Fatal(err)
		}
		remote := netip.MustParseAddrPort(tc.remote)
		if _, err := pc.WriteTo([]byte("ping"), net.UDPAddrFromAddrPort(remote)); err != nil {
			t.Fatal(err)
		}
		uc := r.nextUDPSession()
		deadline := time.Now().Add(5 * time.Second)
		for uc.sentCount() == 0 && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
		uc.mu.Lock()
		sent := append([]udpMsg(nil), uc.sent...)
		uc.mu.Unlock()
		if len(sent) != 1 || string(sent[0].data) != "ping" || sent[0].addr != remote.String() {
			t.Errorf("%s: sent through the tunnel %v, want one ping to %s", tc.remote, sent, remote)
		}

		uc.inject([]byte("pong"), tc.from)
		_ = pc.SetReadDeadline(time.Now().Add(5 * time.Second))
		b := make([]byte, 64)
		n, from, err := pc.ReadFrom(b)
		if err != nil || string(b[:n]) != "pong" {
			t.Errorf("%s: reply %q, err = %v", tc.remote, b[:n], err)
		} else if got := from.(*net.UDPAddr).AddrPort(); got != remote {
			t.Errorf("%s: reply came from %s", tc.remote, got)
		}
		pc.Close()
	}
	r.stop()
}

func TestTunStack_answersDNSOnEveryResolverAddress(t *testing.T) {
	r := newTunStackRig(t, true)
	for _, resolver := range []string{"172.19.0.2:53", "8.8.8.8:53", "[fdfe:dcba:9876::2]:53"} {
		ra, proto := stackFullAddr(resolver)
		pc, err := gonet.DialUDP(r.app, nil, &ra, proto)
		if err != nil {
			t.Fatal(err)
		}
		_ = pc.SetDeadline(time.Now().Add(5 * time.Second))
		if _, err := pc.Write(dnsQuery("example.com")); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, 512)
		n, err := pc.Read(b)
		if err != nil || n < 12 || b[0] != 0x12 || b[1] != 0x34 || !bytes.Contains(b[:n], []byte{9, 9, 9, 9}) {
			t.Errorf("UDP DNS via %s: %d bytes, err = %v", resolver, n, err)
		}
		pc.Close()

		c, err := r.dialTCP(resolver)
		if err != nil {
			t.Fatalf("TCP DNS via %s: dial: %v", resolver, err)
		}
		_ = c.SetDeadline(time.Now().Add(5 * time.Second))
		if err := writeDNSFrame(c, dnsQuery("example.com")); err != nil {
			t.Fatal(err)
		}
		resp, err := readDNSFrame(c)
		if err != nil || len(resp) < 12 || !bytes.Contains(resp, []byte{9, 9, 9, 9}) {
			t.Errorf("TCP DNS via %s: %d bytes, err = %v", resolver, len(resp), err)
		}
		c.Close()
	}
	r.stop()
}

func TestTunStack_stopsWithLiveFlowsAndStartsAgain(t *testing.T) {
	r := newTunStackRig(t, true)
	var conns []*gonet.TCPConn
	for range 20 {
		c, err := r.dialTCP(stackEchoAddr)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, c)
	}
	var pcs []*gonet.UDPConn
	for i := range 20 {
		la, proto := stackFullAddr(fmt.Sprintf("172.19.0.1:%d", 41000+i))
		pc, err := gonet.DialUDP(r.app, &la, nil, proto)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = pc.WriteTo([]byte("x"), &net.UDPAddr{IP: net.IPv4(203, 0, 113, 7), Port: 5000})
		pcs = append(pcs, pc)
	}
	for range 20 {
		r.nextUDPSession()
	}
	start := time.Now()
	if err := r.sess.StopTUN(); err != nil {
		t.Errorf("StopTUN with live flows after %s: %v", time.Since(start), err)
	}
	for _, c := range conns {
		c.Close()
	}
	for _, pc := range pcs {
		pc.Close()
	}
	r.stop()

	r.start(true)
	c, err := r.dialTCP(stackEchoAddr)
	if err != nil {
		t.Fatalf("after restart: %v", err)
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = c.Write([]byte("again"))
	b := make([]byte, 5)
	if _, err := io.ReadFull(c, b); err != nil || string(b) != "again" {
		t.Errorf("echo after restart: %q, err = %v", b, err)
	}
	c.Close()
	r.stop()
}

func stackHeapInuse() uint64 {
	runtime.GC()
	runtime.GC()
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return m.HeapInuse
}

func TestTunStack_stalledUploadsHoldOnlyTheirSegments(t *testing.T) {
	r := newTunStackRig(t, true)
	base := stackHeapInuse()
	const flows = 16
	var wg sync.WaitGroup
	var conns []*gonet.TCPConn
	var written atomic.Int64
	for range flows {
		c, err := r.dialTCP(stackStalledAddr)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, c)
		wg.Go(func() {
			_ = c.SetWriteDeadline(time.Now().Add(3 * time.Second))
			chunk := make([]byte, 16<<10)
			for {
				n, err := c.Write(chunk)
				written.Add(int64(n))
				if err != nil {
					return
				}
			}
		})
	}
	wg.Wait()
	grown := int64(stackHeapInuse()) - int64(base)
	if written.Load() < flows<<10 {
		t.Errorf("uploads wrote %d bytes before stalling", written.Load())
	}
	if grown > 128<<20 {
		t.Errorf("%d stalled uploads of %d KiB grew the heap by %d MiB, want at most 128 MiB", flows, written.Load()>>10, grown>>20)
	}
	for _, c := range conns {
		c.Close()
	}
	for range flows {
		select {
		case b := <-r.stalled:
			b.Close()
		default:
		}
	}
	r.stop()
}
