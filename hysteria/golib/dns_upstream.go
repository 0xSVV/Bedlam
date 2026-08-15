package golib

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const dnsAttemptTimeout = 5 * time.Second

const (
	dnsTransportUDP   = "udp"
	dnsTransportTCP   = "tcp"
	dnsTransportTLS   = "tls"
	dnsTransportHTTPS = "https"
	dnsTransportHTTP3 = "http3"
)

type dnsUpstreamConfig struct {
	Transport string   `json:"transport"`
	Servers   []string `json:"servers"`
	Listen    []string `json:"listen"`
}

func parseDNSUpstream(jsonStr string) (*dnsUpstreamConfig, error) {
	if strings.TrimSpace(jsonStr) == "" {
		return nil, errors.New("dns upstream config is empty")
	}
	var cfg dnsUpstreamConfig
	if err := json.Unmarshal([]byte(jsonStr), &cfg); err != nil {
		return nil, fmt.Errorf("invalid dns upstream JSON: %w", err)
	}
	return &cfg, nil
}

type dnsUpstream struct {
	transport string
	servers   []string
	resolvers []dnsResolver
	listen    []netip.Addr
	preferred atomic.Int32
	ident     string
}

func newDNSUpstream(c client.Client, cfg *dnsUpstreamConfig) (*dnsUpstream, error) {
	transport := strings.ToLower(strings.TrimSpace(cfg.Transport))
	if len(cfg.Servers) == 0 {
		return nil, errors.New("dns upstream needs at least one server")
	}
	up := &dnsUpstream{transport: transport}
	for _, raw := range cfg.Servers {
		server, err := normalizeDNSServer(transport, raw)
		if err != nil {
			up.close()
			return nil, err
		}
		r, err := newDNSResolver(c, transport, server)
		if err != nil {
			up.close()
			return nil, err
		}
		up.servers = append(up.servers, server)
		up.resolvers = append(up.resolvers, r)
	}
	for _, l := range cfg.Listen {
		addr, err := netip.ParseAddr(strings.TrimSpace(l))
		if err != nil {
			up.close()
			return nil, fmt.Errorf("invalid dns listen address %q: %w", l, err)
		}
		up.listen = append(up.listen, addr.Unmap())
	}
	up.ident = transport + "|" + strings.Join(up.servers, ",")
	return up, nil
}

func newDNSResolver(c client.Client, transport, server string) (dnsResolver, error) {
	switch transport {
	case dnsTransportTCP:
		return &tcpResolver{client: c, server: server}, nil
	case dnsTransportUDP:
		return newUDPResolver(c, server), nil
	default:
		return nil, fmt.Errorf("unsupported dns transport %q", transport)
	}
}

func (u *dnsUpstream) exchange(ctx context.Context, query []byte) ([]byte, error) {
	n := len(u.resolvers)
	if n == 0 {
		return nil, errors.New("dns upstream has no resolvers")
	}
	start := int(u.preferred.Load()) % n
	var lastErr error
	for i := 0; i < n; i++ {
		if ctx.Err() != nil {
			break
		}
		idx := (start + i) % n
		r := u.resolvers[idx]
		actx, cancel := context.WithTimeout(ctx, dnsAttemptTimeout)
		resp, err := r.exchange(actx, query)
		cancel()
		if err == nil {
			if idx != start {
				u.preferred.Store(int32(idx))
			}
			return resp, nil
		}
		lastErr = err
		if i+1 < n && dnsFailoverLimiter.allow(r.id()) {
			log(LogLevelWarn, srcDNS, "DNS %s failed, trying next: %s", r.id(), err)
		}
	}
	if lastErr == nil {
		lastErr = ctx.Err()
	}
	return nil, lastErr
}

func (u *dnsUpstream) id() string { return u.ident }

func (u *dnsUpstream) close() {
	for _, r := range u.resolvers {
		r.close()
	}
}

func (u *dnsUpstream) isListenAddr(addr netip.Addr) bool {
	addr = addr.Unmap()
	for _, l := range u.listen {
		if l == addr {
			return true
		}
	}
	return false
}

func normalizeDNSServer(transport, raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", errors.New("dns server address is empty")
	}
	switch transport {
	case dnsTransportUDP, dnsTransportTCP:
		return normalizeHostPort(raw, "53")
	case dnsTransportTLS:
		return normalizeHostPort(raw, "853")
	case dnsTransportHTTPS, dnsTransportHTTP3:
		return normalizeDoHURL(raw)
	default:
		return "", fmt.Errorf("unsupported dns transport %q", transport)
	}
}

func normalizeHostPort(raw, defaultPort string) (string, error) {
	host, port, err := splitHostOptionalPort(raw)
	if err != nil {
		return "", err
	}
	if port == "" {
		port = defaultPort
	}
	if err := checkPort(port); err != nil {
		return "", fmt.Errorf("dns server %q: %w", raw, err)
	}
	return net.JoinHostPort(host, port), nil
}

func splitHostOptionalPort(raw string) (host, port string, err error) {
	if strings.HasPrefix(raw, "[") {
		end := strings.IndexByte(raw, ']')
		if end < 0 {
			return "", "", fmt.Errorf("dns server %q: unclosed bracket", raw)
		}
		host = raw[1:end]
		rest := raw[end+1:]
		if rest == "" {
			return host, "", nil
		}
		if !strings.HasPrefix(rest, ":") {
			return "", "", fmt.Errorf("dns server %q: unexpected %q after address", raw, rest)
		}
		return host, rest[1:], nil
	}
	if _, perr := netip.ParseAddr(raw); perr == nil {
		return raw, "", nil
	}
	if h, p, serr := net.SplitHostPort(raw); serr == nil {
		if h == "" {
			return "", "", fmt.Errorf("dns server %q: missing host", raw)
		}
		return h, p, nil
	}
	if strings.Contains(raw, ":") {
		return "", "", fmt.Errorf("dns server %q: invalid address", raw)
	}
	return raw, "", nil
}

func checkPort(port string) error {
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return fmt.Errorf("invalid port %q", port)
	}
	return nil
}

func normalizeDoHURL(raw string) (string, error) {
	if !strings.Contains(raw, "://") {
		host, port, err := splitHostOptionalPort(raw)
		if err != nil {
			return "", err
		}
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
		}
		if port != "" {
			if err := checkPort(port); err != nil {
				return "", fmt.Errorf("dns server %q: %w", raw, err)
			}
			host += ":" + port
		}
		return "https://" + host + "/dns-query", nil
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", fmt.Errorf("dns server %q: %w", raw, err)
	}
	if !strings.EqualFold(u.Scheme, "https") {
		return "", fmt.Errorf("dns server %q: only https:// is supported", raw)
	}
	if u.Hostname() == "" {
		return "", fmt.Errorf("dns server %q: missing host", raw)
	}
	if u.User != nil {
		return "", fmt.Errorf("dns server %q: user info is not allowed", raw)
	}
	if p := u.Port(); p != "" {
		if err := checkPort(p); err != nil {
			return "", fmt.Errorf("dns server %q: %w", raw, err)
		}
	}
	u.Scheme = "https"
	if u.Path == "" {
		u.Path = "/dns-query"
	}
	u.Fragment = ""
	return u.String(), nil
}

func dohDialAddr(rawURL string) (host, dial string, err error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", "", err
	}
	host = u.Hostname()
	port := u.Port()
	if port == "" {
		port = "443"
	}
	return host, net.JoinHostPort(host, port), nil
}

var dnsFailoverLimiter = newRateLimiter(2 * time.Second)
