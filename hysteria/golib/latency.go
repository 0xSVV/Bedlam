package golib

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"sync"
	"sync/atomic"
	"time"
)

// Slots remain occupied until native work actually stops, including a core TCP
// open that cannot be interrupted. Repeated UI cancellation cannot grow workers.
var latencySlots = make(chan struct{}, 3)

// LatencyProbe is single-use. Cancel never closes a borrowed VPN session.
type LatencyProbe struct {
	ctx       context.Context
	cancel    context.CancelFunc
	session   *Session
	owned     bool
	started   atomic.Bool
	closeOnce sync.Once
	tlsConfig *tls.Config // test-only endpoint trust; never inherited from a profile
}

func (s *Session) lifetimeContext() context.Context {
	if s.lifetime != nil {
		return s.lifetime
	}
	return context.Background()
}

// NewLatencyProbe returns nil when opening a TCP stream waits for the server's
// response. The caller must then use NewProfileLatencyProbe so cancellation can
// close that probe's session without interrupting the active VPN.
func (s *Session) NewLatencyProbe() *LatencyProbe {
	if !s.fastOpen {
		return nil
	}
	return s.newLatencyProbe()
}

func (s *Session) newLatencyProbe() *LatencyProbe {
	ctx, cancel := context.WithCancel(s.lifetimeContext())
	return &LatencyProbe{ctx: ctx, cancel: cancel, session: s}
}

// NewProfileLatencyProbe does no network IO. Lazy creation makes the session
// available for cancellation before DNS, Realm discovery or QUIC authentication.
func NewProfileLatencyProbe(configJSON, directDNSJSON string, protector FdProtector) (*LatencyProbe, error) {
	if err := ValidateConfig(configJSON); err != nil {
		return nil, err
	}
	var cfg clientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return nil, err
	}
	cfg.Lazy = true
	raw, err := json.Marshal(cfg)
	if err != nil {
		return nil, err
	}
	s, err := NewSession(string(raw), directDNSJSON, protector, nil)
	if err != nil {
		return nil, err
	}
	p := s.newLatencyProbe()
	p.owned = true
	return p, nil
}

func (p *LatencyProbe) Cancel() {
	p.cancel()
	// Closing the owned session also interrupts QUIC handshakes and Realm UDP.
	// Finish session cleanup off the caller's thread because Close may wait for
	// a core operation that is already unwinding.
	if p.owned {
		p.closeOnce.Do(func() {
			p.session.cancelLifetime()
			p.session.closeAllActiveConns()
			go func() { _ = p.session.Close() }()
		})
	}
}

// Run measures a fresh HTTPS request after authentication, including the remote
// TCP/TLS handshake and response headers. It does not measure ICMP or QUIC RTT.
// Redirects, non-204 replies, TLS errors and TCP refusals are failures.
func (p *LatencyProbe) Run(endpoint string, timeoutMs int64) *TestResult {
	if !p.started.CompareAndSwap(false, true) {
		return &TestResult{Error: "already used"}
	}
	defer p.Cancel()
	u, err := url.Parse(endpoint)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil {
		return &TestResult{Error: "invalid HTTPS endpoint"}
	}
	if timeoutMs < 1 || timeoutMs > 30000 {
		return &TestResult{Error: "invalid timeout"}
	}
	ctx, cancel := context.WithTimeout(p.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	select {
	case latencySlots <- struct{}{}:
	case <-ctx.Done():
		return latencyContextError(ctx)
	}
	result := make(chan *TestResult, 1)
	go func() {
		defer func() { <-latencySlots }()
		result <- p.measure(ctx, u)
	}()
	select {
	case r := <-result:
		if ctx.Err() != nil {
			return latencyContextError(ctx)
		}
		return r
	case <-ctx.Done():
		return latencyContextError(ctx)
	}
}

func latencyContextError(ctx context.Context) *TestResult {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return &TestResult{Error: "timeout"}
	}
	return &TestResult{Error: "cancelled"}
}

func latencyRequestError(err error) *TestResult {
	// A socket deadline can fire before the context timer publishes its error.
	// Preserve that timeout even if Run receives this result first.
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return &TestResult{Error: "timeout"}
	}
	return &TestResult{Error: "request"}
}

func (p *LatencyProbe) measure(ctx context.Context, endpoint *url.URL) *TestResult {
	if ctx.Err() != nil {
		return latencyContextError(ctx)
	}
	rc := p.session.currentClient()
	if rc == nil {
		return &TestResult{Error: "connection"}
	}
	rc.awaitingFirstUse.Store(false)
	c, err := rc.currentClient("latency")
	if err != nil {
		return &TestResult{Error: "connection"}
	}
	if ctx.Err() != nil {
		return latencyContextError(ctx)
	}

	// Dial happens here instead of http.Transport's detached dial goroutine,
	// keeping the entire blocking native operation inside latencySlots.
	start := time.Now()
	addr := endpoint.Host
	if endpoint.Port() == "" {
		addr = net.JoinHostPort(endpoint.Hostname(), "443")
	}
	conn, err := c.TCP(addr)
	if err != nil {
		return latencyRequestError(err)
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if ctx.Err() != nil {
		return latencyContextError(ctx)
	}
	transport := &http.Transport{
		DialContext:            func(context.Context, string, string) (net.Conn, error) { return conn, nil },
		TLSClientConfig:        p.tlsConfig,
		DisableKeepAlives:      true,
		MaxResponseHeaderBytes: 16 * 1024,
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport:     transport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return &TestResult{Error: "request"}
	}
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := client.Do(req)
	if err != nil {
		return latencyRequestError(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return &TestResult{Error: fmt.Sprintf("HTTP %d", resp.StatusCode)}
	}
	return &TestResult{Ok: true, ElapsedMs: max(1, time.Since(start).Milliseconds()), Detail: endpoint.Host}
}
