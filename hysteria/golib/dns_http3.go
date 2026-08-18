package golib

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/quic-go"
	"github.com/apernet/quic-go/http3"
)

type h3Conn struct {
	pkt *hyPacketConn
	tr  *quic.Transport
}

func (c *h3Conn) close() {
	_ = c.tr.Close()
	_ = c.pkt.Close()
}

type h3Resolver struct {
	client   client.Client
	url      string
	dial     string
	tlsCfg   *tls.Config
	fallback *httpsResolver
	rt       *http3.Transport
	hc       *http.Client

	mu      sync.Mutex
	conns   []*h3Conn
	udpDown bool
}

const maxH3Conns = 4

func newH3Resolver(c client.Client, rawURL string, base *tls.Config) (*h3Resolver, error) {
	host, dial, err := dohDialAddr(rawURL)
	if err != nil {
		return nil, fmt.Errorf("DoH3 server %q: %w", rawURL, err)
	}
	fallback, err := newHTTPSResolver(c, rawURL, base)
	if err != nil {
		return nil, err
	}
	r := &h3Resolver{
		client:   c,
		url:      rawURL,
		dial:     dial,
		tlsCfg:   dnsTLSConfig(base, host, []string{http3.NextProtoH3}),
		fallback: fallback,
	}
	r.rt = &http3.Transport{
		TLSClientConfig: r.tlsCfg,
		QUICConfig: &quic.Config{
			MaxIdleTimeout: 60 * time.Second,
			// No keepalive: an idle resolver connection would otherwise wake
			// the radio forever on top of the tunnel's own keepalive. The
			// transport redials when a lookup finds the connection gone.
			KeepAlivePeriod:         0,
			HandshakeIdleTimeout:    5 * time.Second,
			InitialPacketSize:       1200,
			DisablePathMTUDiscovery: true,
		},
		Dial:               r.dialQUIC,
		DisableCompression: true,
	}
	r.hc = &http.Client{
		Transport: r.rt,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return r, nil
}

func (r *h3Resolver) dialQUIC(ctx context.Context, _ string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error) {
	udp, err := r.client.UDP()
	if err != nil {
		return nil, err
	}
	pkt := newHyPacketConn(udp, r.dial)
	tr := &quic.Transport{Conn: pkt}
	qc, err := tr.Dial(ctx, pkt.RemoteAddr(), tlsCfg, cfg)
	if err != nil {
		_ = tr.Close()
		_ = pkt.Close()
		return nil, err
	}
	// http3.Transport drops a client without closing its connection so
	// already-issued requests can drain; tearing it down here would abort
	// every query still in flight on it.
	r.mu.Lock()
	r.conns = append(r.conns, &h3Conn{pkt: pkt, tr: tr})
	stale := r.pruneLocked()
	r.mu.Unlock()
	for _, c := range stale {
		c.close()
	}
	return qc, nil
}

func (r *h3Resolver) pruneLocked() []*h3Conn {
	var keep, stale []*h3Conn
	for _, c := range r.conns {
		if c.pkt.isClosed() {
			stale = append(stale, c)
			continue
		}
		keep = append(keep, c)
	}
	for len(keep) > maxH3Conns {
		stale = append(stale, keep[0])
		keep = keep[1:]
	}
	r.conns = keep
	return stale
}

func (r *h3Resolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if r.isUDPDown() {
		return r.fallback.exchange(ctx, query)
	}
	resp, err := dohExchange(ctx, r.hc, r.url, query)
	if err == nil {
		return resp, nil
	}
	var dialErr coreErrs.DialError
	if errors.As(err, &dialErr) {
		r.markUDPDown()
		if udpDisabledLimiter.allow(r.url) {
			log(LogLevelWarn, srcDNS, "UDP relay unavailable (%s); using DoH over TCP for %s", dialErr, r.url)
		}
		return r.fallback.exchange(ctx, query)
	}
	return nil, err
}

func (r *h3Resolver) id() string { return "http3|" + r.url }

func (r *h3Resolver) close() {
	_ = r.rt.Close()
	r.mu.Lock()
	conns := r.conns
	r.conns = nil
	r.mu.Unlock()
	for _, c := range conns {
		c.close()
	}
	r.fallback.close()
}

func (r *h3Resolver) isUDPDown() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.udpDown
}

func (r *h3Resolver) markUDPDown() {
	r.mu.Lock()
	r.udpDown = true
	r.mu.Unlock()
}
