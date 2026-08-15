package golib

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const (
	dotPoolSize    = 4
	dotIdleTimeout = 30 * time.Second
)

type dotConn struct {
	conn *tls.Conn
	last time.Time
}

type tlsResolver struct {
	client client.Client
	server string
	tlsCfg *tls.Config
	pool   chan *dotConn
	closed atomic.Bool
}

func newTLSResolver(c client.Client, server string, base *tls.Config) *tlsResolver {
	host, _, _ := net.SplitHostPort(server)
	cfg := dnsTLSConfig(base, host, []string{"dot"})
	return &tlsResolver{
		client: c,
		server: server,
		tlsCfg: cfg,
		pool:   make(chan *dotConn, dotPoolSize),
	}
}

func dnsTLSConfig(base *tls.Config, host string, alpn []string) *tls.Config {
	cfg := &tls.Config{}
	if base != nil {
		cfg = base.Clone()
	}
	if cfg.ServerName == "" {
		cfg.ServerName = host
	}
	if cfg.MinVersion == 0 {
		cfg.MinVersion = tls.VersionTLS12
	}
	if cfg.RootCAs == nil && !cfg.InsecureSkipVerify {
		cfg.RootCAs = dnsRootCAs()
	}
	if cfg.NextProtos == nil {
		cfg.NextProtos = alpn
	}
	if cfg.ClientSessionCache == nil {
		cfg.ClientSessionCache = tls.NewLRUClientSessionCache(8)
	}
	return cfg
}

func (r *tlsResolver) id() string { return "tls|" + r.server }

func (r *tlsResolver) close() {
	r.closed.Store(true)
	for {
		select {
		case c := <-r.pool:
			_ = c.conn.Close()
		default:
			return
		}
	}
}

func (r *tlsResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if c := r.takeIdle(); c != nil {
		if resp, err := r.exchangeOn(ctx, c, query); err == nil {
			return resp, nil
		} else if ctx.Err() != nil {
			return nil, err
		}
	}
	c, err := r.dial(ctx)
	if err != nil {
		return nil, err
	}
	return r.exchangeOn(ctx, c, query)
}

func (r *tlsResolver) exchangeOn(ctx context.Context, c *dotConn, query []byte) ([]byte, error) {
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(dnsIOTimeout)
	}
	_ = c.conn.SetDeadline(deadline)
	resp, err := dnsStreamExchange(c.conn, query)
	if err != nil {
		_ = c.conn.Close()
		return nil, fmt.Errorf("DoT %s: %w", r.server, err)
	}
	_ = c.conn.SetDeadline(time.Time{})
	c.last = time.Now()
	r.putIdle(c)
	return resp, nil
}

func (r *tlsResolver) dial(ctx context.Context) (*dotConn, error) {
	raw, err := dialTunnelTCP(ctx, r.client, r.server)
	if err != nil {
		return nil, fmt.Errorf("dial DoT server %s: %w", r.server, err)
	}
	tc := tls.Client(raw, r.tlsCfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		_ = raw.Close()
		return nil, fmt.Errorf("DoT handshake with %s: %w", r.server, err)
	}
	return &dotConn{conn: tc}, nil
}

func (r *tlsResolver) takeIdle() *dotConn {
	for {
		select {
		case c := <-r.pool:
			if time.Since(c.last) > dotIdleTimeout {
				_ = c.conn.Close()
				continue
			}
			return c
		default:
			return nil
		}
	}
}

func (r *tlsResolver) putIdle(c *dotConn) {
	if r.closed.Load() {
		_ = c.conn.Close()
		return
	}
	select {
	case r.pool <- c:
	default:
		_ = c.conn.Close()
	}
}

func dialTunnelTCP(ctx context.Context, c client.Client, addr string) (net.Conn, error) {
	type result struct {
		conn net.Conn
		err  error
	}
	done := make(chan result, 1)
	go func() {
		conn, err := c.TCP(addr)
		done <- result{conn, err}
	}()
	select {
	case r := <-done:
		return r.conn, r.err
	case <-ctx.Done():
		go func() {
			if r := <-done; r.conn != nil {
				_ = r.conn.Close()
			}
		}()
		return nil, ctx.Err()
	}
}

var (
	dnsRootsOnce sync.Once
	dnsRoots     *x509.CertPool
)

func dnsRootCAs() *x509.CertPool {
	dnsRootsOnce.Do(func() {
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			pool = x509.NewCertPool()
		}
		if runtime.GOOS == "android" {
			appendPEMDir(pool, "/apex/com.android.conscrypt/cacerts")
		}
		dnsRoots = pool
	})
	return dnsRoots
}

func appendPEMDir(pool *x509.CertPool, dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err == nil {
			pool.AppendCertsFromPEM(data)
		}
	}
}
