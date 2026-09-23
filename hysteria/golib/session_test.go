package golib

import (
	"context"
	"errors"
	"net"
	"testing"
)

func sessionWithCachedAnswer(t *testing.T) (*Session, *stubResolver, []byte, net.PacketConn) {
	t.Helper()
	conn, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	s := &Session{
		activeConns: map[net.PacketConn]struct{}{conn: {}},
		dnsCache:    newDNSCache(),
	}
	r := &stubResolver{name: "stub", reply: echoAnswer([4]byte{1, 2, 3, 4})}
	q := dnsQuery("example.com")
	if _, err := s.dnsCache.resolve(context.Background(), r, q, nil); err != nil {
		t.Fatal(err)
	}
	if s.dnsCache.tryCached(r, q) == nil {
		t.Fatal("the answer was not cached")
	}
	return s, r, q, conn
}

func assertClosed(t *testing.T, conn net.PacketConn) {
	t.Helper()
	if _, _, err := conn.ReadFrom(make([]byte, 1)); !errors.Is(err, net.ErrClosed) {
		t.Errorf("active connection read err = %v, want net.ErrClosed", err)
	}
}

func TestResetConnectionsKeepingDNSCache_keepsCachedAnswers(t *testing.T) {
	s, r, q, conn := sessionWithCachedAnswer(t)

	s.ResetConnectionsKeepingDNSCache()

	if s.dnsCache.tryCached(r, q) == nil {
		t.Error("a network change dropped the cached answer")
	}
	assertClosed(t, conn)
}

func TestResetConnections_clearsCachedAnswers(t *testing.T) {
	s, r, q, conn := sessionWithCachedAnswer(t)

	s.ResetConnections()

	if s.dnsCache.tryCached(r, q) != nil {
		t.Error("a user reset kept the cached answer")
	}
	assertClosed(t, conn)
}
