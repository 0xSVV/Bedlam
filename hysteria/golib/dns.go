package golib

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

func dnsOverTCP(c client.Client, dnsServer string, query []byte) ([]byte, error) {
	conn, err := c.TCP(dnsServer)
	if err != nil {
		return nil, fmt.Errorf("dial DNS server: %w", err)
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(10 * time.Second))

	msg := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(msg[:2], uint16(len(query)))
	copy(msg[2:], query)
	if _, err := conn.Write(msg); err != nil {
		return nil, fmt.Errorf("write query: %w", err)
	}

	var respLen [2]byte
	if _, err := io.ReadFull(conn, respLen[:]); err != nil {
		return nil, fmt.Errorf("read response length: %w", err)
	}
	n := binary.BigEndian.Uint16(respLen[:])
	if n == 0 || n > 65535 {
		return nil, fmt.Errorf("invalid response length: %d", n)
	}

	resp := make([]byte, n)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}
	return resp, nil
}

func isDNSPort(addr string) bool {
	_, port, err := net.SplitHostPort(addr)
	return err == nil && port == "53"
}
