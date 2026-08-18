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

	"github.com/apernet/hysteria/core/v2/client"
)

type tlsResolver struct {
	server string
	tlsCfg *tls.Config
	pool   *streamPool
}

func newTLSResolver(c client.Client, server string, base *tls.Config) *tlsResolver {
	host, _, _ := net.SplitHostPort(server)
	cfg := dnsTLSConfig(base, host, []string{"dot"})
	return &tlsResolver{
		server: server,
		tlsCfg: cfg,
		pool: newStreamPool("DoT "+server, func(ctx context.Context) (net.Conn, error) {
			raw, err := dialTunnelTCP(ctx, c, server)
			if err != nil {
				return nil, fmt.Errorf("dial DoT server %s: %w", server, err)
			}
			tc := tls.Client(raw, cfg)
			if err := tc.HandshakeContext(ctx); err != nil {
				_ = raw.Close()
				return nil, fmt.Errorf("DoT handshake with %s: %w", server, err)
			}
			return tc, nil
		}),
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

func (r *tlsResolver) close() { r.pool.close() }

func (r *tlsResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	return r.pool.exchange(ctx, query)
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
