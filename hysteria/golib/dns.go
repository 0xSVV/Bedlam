package golib

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const (
	dnsDialTimeout = 6 * time.Second
	dnsIOTimeout   = 5 * time.Second
)

func dnsOverTCP(c client.Client, dnsServer string, query []byte) ([]byte, error) {
	type result struct {
		resp []byte
		err  error
	}
	done := make(chan result, 1)
	var (
		mu       sync.Mutex
		conn     net.Conn
		timedOut bool
	)
	go func() {
		cn, err := c.TCP(dnsServer)
		if err != nil {
			done <- result{nil, fmt.Errorf("dial DNS server: %w", err)}
			return
		}
		defer cn.Close()

		mu.Lock()
		if timedOut {
			mu.Unlock()
			return
		}
		conn = cn
		mu.Unlock()

		cn.SetDeadline(time.Now().Add(dnsIOTimeout))

		msg := make([]byte, 2+len(query))
		binary.BigEndian.PutUint16(msg[:2], uint16(len(query)))
		copy(msg[2:], query)
		if _, err := cn.Write(msg); err != nil {
			done <- result{nil, fmt.Errorf("write query: %w", err)}
			return
		}

		var respLen [2]byte
		if _, err := io.ReadFull(cn, respLen[:]); err != nil {
			done <- result{nil, fmt.Errorf("read response length: %w", err)}
			return
		}
		n := binary.BigEndian.Uint16(respLen[:])
		if n == 0 {
			done <- result{nil, fmt.Errorf("invalid response length: %d", n)}
			return
		}

		resp := make([]byte, n)
		if _, err := io.ReadFull(cn, resp); err != nil {
			done <- result{nil, fmt.Errorf("read response: %w", err)}
			return
		}
		if len(query) >= 2 && len(resp) >= 2 &&
			binary.BigEndian.Uint16(resp[:2]) != binary.BigEndian.Uint16(query[:2]) {
			done <- result{nil, fmt.Errorf("response transaction ID mismatch")}
			return
		}
		done <- result{resp, nil}
	}()

	select {
	case r := <-done:
		return r.resp, r.err
	case <-time.After(dnsDialTimeout):
		mu.Lock()
		timedOut = true
		if conn != nil {
			_ = conn.Close()
		}
		mu.Unlock()
		return nil, fmt.Errorf("DNS query to %s timed out", dnsServer)
	}
}

func isDNSPort(addr string) bool {
	_, port, err := net.SplitHostPort(addr)
	return err == nil && port == "53"
}

func buildServFail(query []byte) []byte {
	if len(query) < 12 {
		return nil
	}
	resp := make([]byte, len(query))
	copy(resp, query)
	resp[2] |= 0x80
	resp[3] = 0x80 | 0x02
	resp[6], resp[7] = 0, 0
	resp[8], resp[9] = 0, 0
	resp[10], resp[11] = 0, 0
	return resp
}
