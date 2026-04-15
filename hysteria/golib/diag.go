package golib

import (
	"fmt"
	"time"
)

func TestUDP() string {
	clientMu.Lock()
	c := activeClient
	clientMu.Unlock()

	if c == nil {
		return "error: client not connected"
	}

	rc, err := c.UDP()
	if err != nil {
		return fmt.Sprintf("error: UDP session failed: %s", err)
	}
	defer rc.Close()

	logMsg(LogLevelInfo, "TestUDP: sending DNS query to 8.8.8.8:53 via QUIC datagram")
	if err := rc.Send(buildDNSQuery(), "8.8.8.8:53"); err != nil {
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
	clientMu.Lock()
	c := activeClient
	clientMu.Unlock()

	if c == nil {
		return "error: client not connected"
	}

	logMsg(LogLevelInfo, "TestDNS: sending DNS query to 8.8.8.8:53 via TCP")
	resp, err := dnsOverTCP(c, "8.8.8.8:53", buildDNSQuery())
	if err != nil {
		return fmt.Sprintf("error: %s", err)
	}
	return fmt.Sprintf("ok: %d bytes", len(resp))
}

func buildDNSQuery() []byte {
	return []byte{
		0x12, 0x34, // Transaction ID
		0x01, 0x00, // Flags: standard query, recursion desired
		0x00, 0x01, // Questions: 1
		0x00, 0x00, // Answers: 0
		0x00, 0x00, // Authority: 0
		0x00, 0x00, // Additional: 0
		0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
		0x03, 'c', 'o', 'm',
		0x00,       // Root label
		0x00, 0x01, // Type A
		0x00, 0x01, // Class IN
	}
}
