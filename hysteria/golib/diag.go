package golib

import (
	"fmt"
	"time"
)

func TestUDP() string {
	clientMutex.Lock()
	c := activeClient
	clientMutex.Unlock()

	if c == nil {
		return "error: client not connected"
	}

	rc, err := c.UDP()
	if err != nil {
		return fmt.Sprintf("error: UDP session failed: %s", err)
	}
	defer rc.Close()

	log(LogLevelInfo, "TestUDP: sending DNS query to 1.1.1.1:53 via QUIC datagram")
	if err := rc.Send(buildDNSQuery(), "1.1.1.1:53"); err != nil {
		return fmt.Sprintf("error: send failed: %s", err)
	}

	type result struct {
		data []byte
		from string
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		data, from, err := rc.Receive()
		ch <- result{data, from, err}
	}()

	select {
	case r := <-ch:
		if r.err != nil {
			return fmt.Sprintf("error: receive failed: %s", r.err)
		}
		return fmt.Sprintf("ok: %d bytes from %s", len(r.data), r.from)
	case <-time.After(10 * time.Second):
		return "error: timeout (outbound UDP port most likely blocked)"
	}
}

func TestDNSOverTCP() string {
	clientMutex.Lock()
	client := activeClient
	clientMutex.Unlock()

	if client == nil {
		return "error: client not connected"
	}

	log(LogLevelInfo, "TestDNS: sending DNS query to 1.1.1.1:53 via TCP")
	resp, err := dnsOverTCP(client, "1.1.1.1:53", buildDNSQuery())
	if err != nil {
		return fmt.Sprintf("error: %s", err)
	}
	return fmt.Sprintf("ok: %d bytes", len(resp))
}

// buildDNSQuery builds an A-record query for cloudflare.com. We avoid
// example.com (often hijacked or short-circuited by transparent DNS
// interceptors) so a successful response actually exercises the tunnel.
func buildDNSQuery() []byte {
	return []byte{
		0x12, 0x34, // Transaction ID
		0x01, 0x00, // Flags: standard query, recursion desired
		0x00, 0x01, // Questions: 1
		0x00, 0x00, // Answers: 0
		0x00, 0x00, // Authority: 0
		0x00, 0x00, // Additional: 0
		0x0a, 'c', 'l', 'o', 'u', 'd', 'f', 'l', 'a', 'r', 'e',
		0x03, 'c', 'o', 'm',
		0x00,       // Root label
		0x00, 0x01, // Type A
		0x00, 0x01, // Class IN
	}
}
