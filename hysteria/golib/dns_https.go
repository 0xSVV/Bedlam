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
	"strings"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const (
	dohContentType = "application/dns-message"
	dohMaxResponse = 64 * 1024
)

type httpsResolver struct {
	client client.Client
	url    string
	dial   string
	tlsCfg *tls.Config
	rt     *http.Transport
	hc     *http.Client
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
	r.rt = &http.Transport{
		DialTLSContext:        r.dialTLS,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          2,
		MaxIdleConnsPerHost:   2,
		IdleConnTimeout:       90 * time.Second,
		ResponseHeaderTimeout: dnsIOTimeout,
		DisableCompression:    true,
	}
	r.hc = &http.Client{
		Transport: r.rt,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return r, nil
}

func (r *httpsResolver) dialTLS(ctx context.Context, _, _ string) (net.Conn, error) {
	raw, err := dialTunnelTCP(ctx, r.client, r.dial)
	if err != nil {
		return nil, err
	}
	tc := tls.Client(raw, r.tlsCfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		_ = raw.Close()
		return nil, err
	}
	return tc, nil
}

func (r *httpsResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	return dohExchange(ctx, r.hc, r.url, query)
}

func (r *httpsResolver) id() string { return "https|" + r.url }

func (r *httpsResolver) close() { r.rt.CloseIdleConnections() }

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
		return nil, fmt.Errorf("DoH %s: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
		return nil, fmt.Errorf("DoH %s: HTTP %d", url, resp.StatusCode)
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
