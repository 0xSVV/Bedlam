package golib

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestDirectDNS_UDPBootstrapRetriesTruncatedAnswerOverTCP(t *testing.T) {
	address, tcpQueries := directTCPTestServer(t)
	udp, err := net.ListenPacket("udp", address)
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		for {
			query := make([]byte, 4096)
			n, peer, err := udp.ReadFrom(query)
			if err != nil {
				return
			}
			query = query[:n]
			query[2] |= 0x82 // Response with TC set: retry this question over TCP.
			query[3] = 0x80
			_, _ = udp.WriteTo(query, peer)
		}
	}()
	session := &Session{
		protector: &directTestProtector{allow: true},
		directDNS: directTestConfig(t, "udp", address),
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	addresses, err := session.lookupDirectHost(ctx, "large-answer.test")
	if err != nil {
		t.Fatalf("truncated UDP bootstrap must retry TCP: %v", err)
	}
	if len(addresses) != 1 || addresses[0].IP.String() != "192.0.2.17" || tcpQueries.Load() == 0 {
		t.Fatalf("addresses=%v TCP queries=%d", addresses, tcpQueries.Load())
	}
}
