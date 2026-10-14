package golib

import (
	"context"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
)

type allowBoundProtector struct{ calls int }

func (p *allowBoundProtector) Protect(int32) bool { return true }
func (p *allowBoundProtector) BindAndProtect(int32) bool {
	p.calls++
	return true
}

func addTestLease(r *domainAddressRegistry, addr netip.Addr, now time.Time) {
	r.entries[domainLeaseKey{address: addr, ruleID: "rule-a", generation: r.generation}] = now.Add(time.Minute)
}

func TestDirectTCPLeaseUsesProtectedSocketAndStaysOutOfTunnelCounters(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			defer conn.Close()
			_, _ = io.Copy(conn, conn)
		}
	}()
	addr := listener.Addr().(*net.TCPAddr).AddrPort()
	protector := &allowBoundProtector{}
	registry := newDomainAddressRegistry(8)
	addTestLease(registry, addr.Addr(), time.Now())
	session := &Session{protector: protector, domainIPs: registry}
	h := &tunHandler{session: session, client: &fakeClient{}}
	app, handler := net.Pipe()
	destination := M.SocksaddrFromNetIP(addr)
	done := make(chan error, 1)
	go func() { done <- h.newConnection(context.Background(), handler, M.Socksaddr{}, destination) }()
	if _, err := app.Write([]byte("direct tcp")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len("direct tcp"))
	if _, err := io.ReadFull(app, got); err != nil {
		t.Fatal(err)
	}
	if string(got) != "direct tcp" {
		t.Fatalf("echo=%q", got)
	}
	_ = app.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("direct TCP flow did not close")
	}
	if protector.calls != 1 {
		t.Fatalf("bind/protect calls=%d, want 1", protector.calls)
	}
	if session.txBytes.Load() != 0 || session.rxBytes.Load() != 0 {
		t.Fatalf("direct bytes counted as tunnel traffic: tx=%d rx=%d", session.txBytes.Load(), session.rxBytes.Load())
	}
}

func TestDirectUDPLeaseUsesProtectedConnectedSocket(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	go func() {
		buffer := make([]byte, 256)
		n, addr, err := server.ReadFrom(buffer)
		if err == nil {
			_, _ = server.WriteTo(buffer[:n], addr)
		}
	}()
	addr := server.LocalAddr().(*net.UDPAddr).AddrPort()
	protector := &allowBoundProtector{}
	registry := newDomainAddressRegistry(8)
	addTestLease(registry, addr.Addr(), time.Now())
	h := &tunHandler{session: &Session{protector: protector, domainIPs: registry}, client: &fakeClient{}}
	packets := newFakePacketConn()
	destination := M.SocksaddrFromNetIP(addr)
	packets.in <- fakePacket{data: []byte("direct udp"), addr: M.Socksaddr{}}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- h.newPacketConnection(ctx, packets, M.Socksaddr{}, destination) }()
	select {
	case response := <-packets.out:
		if string(response.data) != "direct udp" || response.addr != destination {
			t.Fatalf("response=%+v", response)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("direct UDP response was not relayed")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("direct UDP flow did not close")
	}
	if protector.calls != 1 {
		t.Fatalf("bind/protect calls=%d, want 1", protector.calls)
	}
}
