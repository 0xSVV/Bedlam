package golib

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

// dnsOverTCP performs a DNS-over-TCP query with a hard overall timeout.
// The dial itself (c.TCP) has no context support in hysteria core, so we
// wrap it in a goroutine and abandon it on timeout — the stuck goroutine
// will finish on its own when the underlying QUIC stream closes.
func dnsOverTCP(c client.Client, dnsServer string, query []byte) ([]byte, error) {
	type result struct {
		resp []byte
		err  error
	}
	done := make(chan result, 1)
	go func() {
		conn, err := c.TCP(dnsServer)
		if err != nil {
			done <- result{nil, fmt.Errorf("dial DNS server: %w", err)}
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))

		msg := make([]byte, 2+len(query))
		binary.BigEndian.PutUint16(msg[:2], uint16(len(query)))
		copy(msg[2:], query)
		if _, err := conn.Write(msg); err != nil {
			done <- result{nil, fmt.Errorf("write query: %w", err)}
			return
		}

		var respLen [2]byte
		if _, err := io.ReadFull(conn, respLen[:]); err != nil {
			done <- result{nil, fmt.Errorf("read response length: %w", err)}
			return
		}
		n := binary.BigEndian.Uint16(respLen[:])
		if n == 0 || n > 65535 {
			done <- result{nil, fmt.Errorf("invalid response length: %d", n)}
			return
		}

		resp := make([]byte, n)
		if _, err := io.ReadFull(conn, resp); err != nil {
			done <- result{nil, fmt.Errorf("read response: %w", err)}
			return
		}
		done <- result{resp, nil}
	}()

	select {
	case r := <-done:
		return r.resp, r.err
	case <-time.After(6 * time.Second):
		return nil, fmt.Errorf("DNS query to %s timed out", dnsServer)
	}
}

func isDNSPort(addr string) bool {
	_, port, err := net.SplitHostPort(addr)
	return err == nil && port == "53"
}
