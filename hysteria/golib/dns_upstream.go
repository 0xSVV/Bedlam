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
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

const (
	dnsAttemptTimeout     = 5 * time.Second
	dnsMinAttemptTimeout  = 1500 * time.Millisecond
	dnsPreferenceCooldown = 5 * time.Minute
)

const (
	dnsTransportUDP   = "udp"
	dnsTransportTCP   = "tcp"
	dnsTransportTLS   = "tls"
	dnsTransportQUIC  = "quic"
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
	ident     string
	now       func() time.Time

	mu             sync.Mutex
	preferred      int
	preferredUntil time.Time
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
		return newTCPResolver(c, server), nil
	case dnsTransportUDP:
		return newUDPResolver(c, server), nil
	case dnsTransportTLS:
		return newTLSResolver(c, server, nil), nil
	case dnsTransportQUIC:
		return newDoQResolver(c, server, nil), nil
	case dnsTransportHTTPS:
		return newHTTPSResolver(c, server, nil)
	case dnsTransportHTTP3:
		return newH3Resolver(c, server, nil)
	default:
		return nil, fmt.Errorf("unsupported dns transport %q", transport)
	}
}

type attemptResult struct {
	index int
	resp  []byte
	err   error
}

func (u *dnsUpstream) exchange(ctx context.Context, query []byte) ([]byte, error) {
	n := len(u.resolvers)
	if n == 0 {
		return nil, errors.New("dns upstream has no resolvers")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	began := time.Now()
	queryDeadline, ok := ctx.Deadline()
	if !ok {
		queryDeadline = began.Add(dnsQueryTimeout)
	}
	first := u.firstIndex()
	results := make(chan attemptResult, n)
	var slice *time.Timer
	defer func() {
		if slice != nil {
			slice.Stop()
		}
	}()
	var sliceEnd <-chan time.Time
	var lastCtx context.Context
	var lastSlice <-chan struct{}
	var cancels []context.CancelFunc
	started, pending, latest := 0, 0, first
	latestStart := began
	launch := func() {
		latest = (first + started) % n
		latestStart = time.Now()
		budget := u.attemptBudget(ctx, n-started)
		started++
		pending++
		deadline := queryDeadline
		if started == n && latestStart.Add(budget).Before(deadline) {
			deadline = latestStart.Add(budget)
		}
		actx, cancel := context.WithDeadline(context.WithoutCancel(ctx), deadline)
		cancels = append(cancels, cancel)
		if started < n {
			if slice == nil {
				slice = time.NewTimer(budget)
			} else {
				slice.Reset(budget)
			}
			sliceEnd = slice.C
		} else {
			sliceEnd = nil
			lastCtx, lastSlice = actx, actx.Done()
		}
		index := latest
		go func() {
			defer cancel()
			resp, err := u.resolvers[index].exchange(actx, query)
			results <- attemptResult{index: index, resp: resp, err: err}
		}()
	}

	launch()
	done := ctx.Done()
	var lastErr error
	tunnelFailed, latestFailed, stopping := false, false, false
	for {
		select {
		case res := <-results:
			pending--
			if res.err == nil {
				u.noteAnswer(first, res.index, tunnelFailed)
				u.settleLate(ctx, results, pending)
				return res.resp, nil
			}
			lastErr = res.err
			tunnelFailed = tunnelFailed || isTunnelFailure(res.err)
			if res.index != latest {
				break
			}
			latestFailed = true
			if started < n && !stopping {
				if id := u.resolvers[latest].id(); dnsFailoverLimiter.allow(id) {
					log(LogLevelWarn, srcDNS, "DNS %s failed, trying next: %s", id, res.err)
				}
				latestFailed = false
				launch()
			}
		case <-sliceEnd:
			if id := u.resolvers[latest].id(); dnsFailoverLimiter.allow(id) {
				log(LogLevelWarn, srcDNS, "DNS %s has not answered in %s, trying next", id, diagDuration(time.Since(latestStart)))
			}
			latestFailed = false
			launch()
		case <-lastSlice:
			lastSlice = nil
		case <-done:
			done, sliceEnd, stopping = nil, nil, true
			if !errors.Is(ctx.Err(), context.DeadlineExceeded) {
				for _, cancel := range cancels {
					cancel()
				}
			}
		}
		lastSliceEnded := lastCtx != nil && errors.Is(lastCtx.Err(), context.DeadlineExceeded)
		if lastSliceEnded && latestFailed || pending == 0 && (started == n || stopping) {
			u.settleLate(ctx, results, pending)
			if lastSliceEnded {
				return nil, fmt.Errorf("no answer in %s: %w", diagDuration(time.Since(began)), context.DeadlineExceeded)
			}
			return nil, lastErr
		}
	}
}

func (u *dnsUpstream) clock() time.Time {
	if u.now != nil {
		return u.now()
	}
	return time.Now()
}

func (u *dnsUpstream) firstIndex() int {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.preferred != 0 && u.clock().Before(u.preferredUntil) {
		return u.preferred
	}
	return 0
}

func (u *dnsUpstream) noteAnswer(first, answered int, tunnelFailed bool) {
	movedAway := answered != 0 && answered != first && !tunnelFailed
	u.mu.Lock()
	previous := u.preferred
	switch {
	case answered == 0:
		u.preferred = 0
	case movedAway:
		u.preferred = answered
		u.preferredUntil = u.clock().Add(dnsPreferenceCooldown)
	}
	u.mu.Unlock()
	switch {
	case movedAway:
		if dnsPreferenceLimiter.allow("away|" + u.ident) {
			log(LogLevelInfo, srcDNS, "DNS now answered by %s; %s did not answer", u.resolvers[answered].id(), u.resolvers[first].id())
		}
	case answered == 0 && previous != 0:
		if dnsPreferenceLimiter.allow("back|" + u.ident) {
			log(LogLevelInfo, srcDNS, "DNS answered by %s again", u.resolvers[0].id())
		}
	}
}

func isTunnelFailure(err error) bool {
	var closed coreErrs.ClosedError
	return errors.Is(err, errDialBackoff) || errors.As(err, &closed) || errors.Is(err, net.ErrClosed)
}

func (u *dnsUpstream) settleLate(ctx context.Context, results <-chan attemptResult, pending int) {
	if pending == 0 {
		return
	}
	late := lateAnswer(ctx)
	go func() {
		for ; pending > 0; pending-- {
			if res := <-results; res.err == nil {
				late(res.resp)
			}
		}
	}()
}

// attemptBudget shares whatever time is left across the servers still to try,
// so a fixed per-attempt timeout cannot make the tail of the list unreachable.
func (u *dnsUpstream) attemptBudget(ctx context.Context, remainingServers int) time.Duration {
	deadline, ok := ctx.Deadline()
	if !ok || remainingServers <= 0 {
		return dnsAttemptTimeout
	}
	per := time.Until(deadline) / time.Duration(remainingServers)
	if per > dnsAttemptTimeout {
		return dnsAttemptTimeout
	}
	if per < dnsMinAttemptTimeout {
		return dnsMinAttemptTimeout
	}
	return per
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
		// IP literals only, matching the app-side validator: a plain-DNS
		// upstream has no certificate to bind a name to, and the UDP reply
		// check compares the source address against this string.
		return normalizeHostPort(raw, "53", false)
	case dnsTransportTLS:
		return normalizeHostPort(raw, "853", true)
	case dnsTransportQUIC:
		endpoint := strings.TrimSpace(strings.TrimPrefix(raw, "quic://"))
		if endpoint == "" {
			return "", fmt.Errorf("dns server %q: missing host", raw)
		}
		return normalizeHostPort(endpoint, "853", true)
	case dnsTransportHTTPS, dnsTransportHTTP3:
		return normalizeDoHURL(raw)
	default:
		return "", fmt.Errorf("unsupported dns transport %q", transport)
	}
}

func normalizeHostPort(raw, defaultPort string, allowHost bool) (string, error) {
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
	if !allowHost {
		if _, perr := netip.ParseAddr(host); perr != nil {
			return "", fmt.Errorf("dns server %q: must be an IP address", raw)
		}
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
		// Ports belong in a full URL: a leftover `1.1.1.1:53` from a
		// plain-DNS transport must not become https://1.1.1.1:53/dns-query.
		if port != "" {
			return "", fmt.Errorf("dns server %q: write a full https:// URL to use a port", raw)
		}
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
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
	if u.Path == "" || u.Path == "/" {
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

var (
	dnsFailoverLimiter   = newRateLimiter(2 * time.Second)
	dnsPreferenceLimiter = newRateLimiter(time.Minute)
)
