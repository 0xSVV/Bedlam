package golib

import (
	"time"
)

// TestResult is the outcome of a connectivity diagnostic. Exactly one of
// Ok or Error carries meaningful data: when Ok is true, Bytes/ElapsedMs/Detail
// describe the successful round-trip; when Ok is false, Error explains the
// failure.
type TestResult struct {
	Ok        bool
	Bytes     int32
	ElapsedMs int64
	Detail    string
	Error     string
}

// TestUDP sends a small DNS query through hysteria's QUIC-datagram UDP relay
// and waits for a response. Returns within ~10 seconds.
func (s *Session) TestUDP() *TestResult {
	c := s.currentClient()
	if c == nil {
		return &TestResult{Error: "client not connected"}
	}

	rc, err := c.UDP()
	if err != nil {
		return &TestResult{Error: "UDP session failed: " + err.Error()}
	}
	defer rc.Close()

	log(LogLevelInfo, srcDNS, "TestUDP: sending DNS query to 1.1.1.1:53 via QUIC datagram")
	start := time.Now()
	if err := rc.Send(buildDNSQuery(), "1.1.1.1:53"); err != nil {
		return &TestResult{Error: "send failed: " + err.Error()}
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
		elapsed := time.Since(start).Milliseconds()
		if r.err != nil {
			return &TestResult{Error: "receive failed: " + r.err.Error()}
		}
		return &TestResult{
			Ok:        true,
			Bytes:     int32(len(r.data)),
			ElapsedMs: elapsed,
			Detail:    r.from,
		}
	case <-time.After(10 * time.Second):
		return &TestResult{Error: "timeout (outbound UDP port most likely blocked)"}
	}
}

// TestDNSOverTCP sends a small DNS query through a hysteria TCP stream and
// waits for the response.
func (s *Session) TestDNSOverTCP() *TestResult {
	c := s.currentClient()
	if c == nil {
		return &TestResult{Error: "client not connected"}
	}

	log(LogLevelInfo, srcDNS, "TestDNS: sending DNS query to 1.1.1.1:53 via TCP")
	start := time.Now()
	resp, err := dnsOverTCP(c, "1.1.1.1:53", buildDNSQuery())
	elapsed := time.Since(start).Milliseconds()
	if err != nil {
		return &TestResult{Error: err.Error()}
	}
	return &TestResult{
		Ok:        true,
		Bytes:     int32(len(resp)),
		ElapsedMs: elapsed,
		Detail:    "1.1.1.1:53",
	}
}

func buildDNSQuery() []byte {
	return []byte{
		0x12, 0x34,
		0x01, 0x00,
		0x00, 0x01,
		0x00, 0x00,
		0x00, 0x00,
		0x00, 0x00,
		0x0a, 'c', 'l', 'o', 'u', 'd', 'f', 'l', 'a', 'r', 'e',
		0x03, 'c', 'o', 'm',
		0x00,
		0x00, 0x01,
		0x00, 0x01,
	}
}
