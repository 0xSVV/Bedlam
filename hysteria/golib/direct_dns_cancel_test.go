package golib

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestDirectDNS_UDPCancellationInterruptsRead(t *testing.T) {
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	received := make(chan struct{})
	go func() {
		buf := make([]byte, 512)
		if _, _, err := server.ReadFrom(buf); err == nil {
			close(received)
		}
	}()
	resolver := &directUDPResolver{
		session: &Session{protector: &directTestProtector{allow: true}},
		server:  server.LocalAddr().String(),
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := resolver.exchange(ctx, dnsQuery("cancel.test"))
		done <- err
	}()
	select {
	case <-received:
	case <-time.After(time.Second):
		t.Fatal("resolver never sent query")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled query unexpectedly succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled Direct DNS read remained blocked")
	}
}

func TestDirectDNS_UnconfiguredLookupReturnsError(t *testing.T) {
	_, err := (&Session{}).lookupDirectHost(context.Background(), "unconfigured.test")
	if err == nil {
		t.Fatal("unconfigured resolver must fail without system DNS fallback")
	}
}
