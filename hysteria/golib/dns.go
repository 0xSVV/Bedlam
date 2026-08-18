package golib

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const (
	dnsDialTimeout  = 6 * time.Second
	dnsIOTimeout    = 5 * time.Second
	dnsQueryTimeout = 10 * time.Second

	dnsHeaderLen = 12
)

var errDNSTimeout = errors.New("dns query timed out")

var errDNSMalformed = errors.New("malformed dns response")

type dnsResolver interface {
	exchange(ctx context.Context, query []byte) ([]byte, error)
	id() string
	close()
}

type tcpResolver struct {
	server string
	pool   *streamPool
}

func newTCPResolver(c client.Client, server string) *tcpResolver {
	return &tcpResolver{
		server: server,
		pool: newStreamPool("DNS over TCP "+server, func(ctx context.Context) (net.Conn, error) {
			conn, err := dialTunnelTCP(ctx, c, server)
			if err != nil {
				return nil, fmt.Errorf("dial DNS server %s: %w", server, err)
			}
			return conn, nil
		}),
	}
}

func (r *tcpResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	return r.pool.exchange(ctx, query)
}

func (r *tcpResolver) id() string { return "tcp|" + r.server }

func (r *tcpResolver) close() { r.pool.close() }

func writeDNSFrame(w io.Writer, msg []byte) error {
	if len(msg) > 0xffff {
		return fmt.Errorf("dns message too large: %d bytes", len(msg))
	}
	framed := make([]byte, 2+len(msg))
	binary.BigEndian.PutUint16(framed[:2], uint16(len(msg)))
	copy(framed[2:], msg)
	_, err := w.Write(framed)
	return err
}

func readDNSFrame(r io.Reader) ([]byte, error) {
	var respLen [2]byte
	if _, err := io.ReadFull(r, respLen[:]); err != nil {
		return nil, fmt.Errorf("read response length: %w", err)
	}
	n := binary.BigEndian.Uint16(respLen[:])
	if n < dnsHeaderLen {
		return nil, fmt.Errorf("%w: response shorter than a DNS header: %d", errDNSMalformed, n)
	}
	resp := make([]byte, n)
	if _, err := io.ReadFull(r, resp); err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}
	return resp, nil
}

// checkDNSTxID fails closed: a response too short to carry a transaction ID
// is malformed, not implicitly trusted. Treating it as success would suppress
// both SERVFAIL synthesis and failover to the next upstream.
func checkDNSTxID(query, resp []byte) error {
	if len(resp) < dnsHeaderLen {
		return fmt.Errorf("%w: response shorter than a DNS header", errDNSMalformed)
	}
	if len(query) < 2 {
		return fmt.Errorf("%w: query shorter than a DNS header", errDNSMalformed)
	}
	if binary.BigEndian.Uint16(resp[:2]) != binary.BigEndian.Uint16(query[:2]) {
		return fmt.Errorf("%w: response transaction ID mismatch", errDNSMalformed)
	}
	return nil
}

func dnsStreamExchange(conn net.Conn, query []byte) ([]byte, error) {
	if err := writeDNSFrame(conn, query); err != nil {
		return nil, fmt.Errorf("write query: %w", err)
	}
	resp, err := readDNSFrame(conn)
	if err != nil {
		return nil, err
	}
	if err := checkDNSTxID(query, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func dnsOverTCP(c client.Client, dnsServer string, query []byte) ([]byte, error) {
	return dnsOverTCPContext(context.Background(), c, dnsServer, query)
}

func dnsOverTCPContext(ctx context.Context, c client.Client, dnsServer string, query []byte) ([]byte, error) {
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

		resp, err := dnsStreamExchange(cn, query)
		done <- result{resp, err}
	}()

	abort := func() {
		mu.Lock()
		timedOut = true
		if conn != nil {
			_ = conn.Close()
		}
		mu.Unlock()
	}

	select {
	case r := <-done:
		return r.resp, r.err
	case <-time.After(dnsDialTimeout):
		abort()
		return nil, fmt.Errorf("DNS query to %s: %w", dnsServer, errDNSTimeout)
	case <-ctx.Done():
		abort()
		return nil, fmt.Errorf("DNS query to %s: %w", dnsServer, ctx.Err())
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
