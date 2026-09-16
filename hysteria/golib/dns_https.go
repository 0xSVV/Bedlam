package golib

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"strings"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const (
	dohContentType = "application/dns-message"
	dohMaxResponse = 64 * 1024
)

type dohStatusError struct {
	status   int
	proto    string
	queryLen int
}

func (e *dohStatusError) Error() string {
	return fmt.Sprintf("HTTP %d over %s for a %d-byte query", e.status, e.proto, e.queryLen)
}

type httpsResolver struct {
	client client.Client
	url    string
	dial   string
	tlsCfg *tls.Config
	active atomic.Pointer[dohTransport]
}

type dohTransport struct {
	rt *http.Transport
	hc *http.Client
}

type dohConn struct {
	net.Conn
	owner    *dohTransport
	reads    atomic.Uint64
	inflight atomic.Int32
	retired  atomic.Bool
}

func (c *dohConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 {
		c.reads.Add(1)
	}
	return n, err
}

func (c *dohConn) release() {
	if c.inflight.Add(-1) == 0 && c.retired.Load() {
		_ = c.Close()
	}
}

func newHTTPSResolver(c client.Client, rawURL string, base *tls.Config) (*httpsResolver, error) {
	host, dial, err := dohDialAddr(rawURL)
	if err != nil {
		return nil, fmt.Errorf("DoH server %q: %w", rawURL, err)
	}
	r := &httpsResolver{
		client: c,
		url:    rawURL,
		dial:   dial,
		tlsCfg: dnsTLSConfig(base, host, []string{"h2", "http/1.1"}),
	}
	r.active.Store(r.newTransport())
	return r, nil
}

func (r *httpsResolver) newTransport() *dohTransport {
	t := &dohTransport{}
	t.rt = &http.Transport{
		DialTLSContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return r.dialTLS(ctx, t)
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          2,
		MaxIdleConnsPerHost:   2,
		IdleConnTimeout:       90 * time.Second,
		ResponseHeaderTimeout: dnsIOTimeout,
		DisableCompression:    true,
	}
	t.hc = &http.Client{
		Transport: t.rt,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return t
}

func (r *httpsResolver) dialTLS(ctx context.Context, owner *dohTransport) (net.Conn, error) {
	raw, err := dialTunnelTCP(ctx, r.client, r.dial)
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", r.dial, err)
	}
	tc := tls.Client(&dohConn{Conn: raw, owner: owner}, r.tlsCfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		_ = raw.Close()
		return nil, fmt.Errorf("TLS handshake with %s: %w", r.dial, err)
	}
	return tc, nil
}

func (r *httpsResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	var conn *dohConn
	var readsBefore uint64
	trace := &httptrace.ClientTrace{GotConn: func(info httptrace.GotConnInfo) {
		if conn != nil {
			conn.release()
			conn = nil
		}
		if tc, ok := info.Conn.(*tls.Conn); ok {
			if c, ok := tc.NetConn().(*dohConn); ok {
				c.inflight.Add(1)
				conn, readsBefore = c, c.reads.Load()
			}
		}
	}}
	resp, err := dohExchange(httptrace.WithClientTrace(ctx, trace), r.active.Load().hc, r.url, query)
	if conn != nil {
		if err != nil && isTimeoutClass(err) && conn.reads.Load() == readsBefore {
			r.retire(conn)
		}
		conn.release()
	}
	return resp, err
}

func (r *httpsResolver) retire(c *dohConn) {
	if r.active.Load() == c.owner {
		r.active.CompareAndSwap(c.owner, r.newTransport())
	}
	c.retired.Store(true)
	c.owner.rt.CloseIdleConnections()
}

func (r *httpsResolver) id() string { return "https|" + r.url }

func (r *httpsResolver) close() { r.active.Load().rt.CloseIdleConnections() }

func dohExchange(ctx context.Context, hc *http.Client, url string, query []byte) ([]byte, error) {
	if len(query) < 12 {
		return nil, fmt.Errorf("%w: query too short", errDNSMalformed)
	}
	origID := binary.BigEndian.Uint16(query[:2])
	body := make([]byte, len(query))
	copy(body, query)
	body[0], body[1] = 0, 0

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("DoH %s: %w", url, err)
	}
	req.Header.Set("Content-Type", dohContentType)
	req.Header.Set("Accept", dohContentType)

	resp, err := hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("DoH %s: request with a %d-byte query: %w", url, len(query), err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("DoH %s: %w", url, &dohStatusError{status: resp.StatusCode, proto: resp.Proto, queryLen: len(query)})
	}
	if ct := resp.Header.Get("Content-Type"); ct != "" && !strings.HasPrefix(ct, dohContentType) {
		return nil, fmt.Errorf("DoH %s: unexpected content type %q", url, ct)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, dohMaxResponse+1))
	if err != nil {
		return nil, fmt.Errorf("DoH %s: read response: %w", url, err)
	}
	if len(data) > dohMaxResponse {
		return nil, fmt.Errorf("DoH %s: response too large", url)
	}
	if len(data) < 12 {
		return nil, fmt.Errorf("DoH %s: %w: response too short", url, errDNSMalformed)
	}
	binary.BigEndian.PutUint16(data[:2], origID)
	return data, nil
}
