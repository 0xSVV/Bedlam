package golib

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	singtun "github.com/sagernet/sing-tun"
)

type Session struct {
	closed atomic.Bool

	clientMu sync.Mutex
	client   *reconnectClient

	tunMu     sync.Mutex
	tunIface  singtun.Tun
	tunStack  singtun.Stack
	tunCancel context.CancelFunc

	protector FdProtector

	activeConnsMu sync.Mutex
	activeConns   map[net.PacketConn]struct{}

	dnsCache *dnsCache

	txBytes atomic.Int64
	rxBytes atomic.Int64
}

func NewSession(configJSON string, protector FdProtector, handler EventHandler) (*Session, error) {
	var cfg clientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return nil, fmt.Errorf("invalid config JSON: %w", err)
	}

	log(LogLevelInfo, srcTunnel, "Starting client for %s (auth=%s)",
		cfg.Server, authSummary(cfg.Auth))

	s := &Session{
		protector:   protector,
		activeConns: map[net.PacketConn]struct{}{},
		dnsCache:    newDNSCache(),
	}

	wrappedHandler := &loggingHandler{inner: handler}
	var lastResolved net.Addr
	rc, err := newReconnectClient(
		func() (*client.Config, error) {
			addr, rerr := resolveHost(cfg.Server)
			if rerr != nil {
				if lastResolved == nil {
					return nil, fmt.Errorf("resolve server: %w", rerr)
				}
				log(LogLevelWarn, srcDNS, "Re-resolve failed, using last known address %s: %s",
					lastResolved.String(), rerr)
				addr = lastResolved
			} else {
				lastResolved = addr
			}
			return buildCoreConfig(&cfg, addr, s)
		},
		wrappedHandler,
	)
	if err != nil {
		log(LogLevelError, srcTunnel, "Connection failed: %s", err.Error())
		return nil, err
	}
	s.client = rc
	return s, nil
}

func ValidateConfig(configJSON string) error {
	var cfg clientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid config JSON: %w", err)
	}
	if cfg.Server == "" {
		return fmt.Errorf("server address required")
	}
	if _, _, err := net.SplitHostPort(cfg.Server); err != nil {
		return fmt.Errorf("invalid server address %q: %w", cfg.Server, err)
	}
	if cfg.TLSCA != "" {
		if !x509AppendOK(cfg.TLSCA) {
			return fmt.Errorf("invalid CA PEM")
		}
	}
	if (cfg.TLSClientCert != "") != (cfg.TLSClientKey != "") {
		return fmt.Errorf("client cert and key must be set together")
	}
	if cfg.TLSClientCert != "" {
		if err := validateClientKeyPair(cfg.TLSClientCert, cfg.TLSClientKey); err != nil {
			return err
		}
	}
	obfs := strings.ToLower(cfg.ObfsType)
	if obfs != "" && obfs != "salamander" {
		return fmt.Errorf("unsupported obfs type %q", cfg.ObfsType)
	}
	if obfs == "salamander" && cfg.ObfsPassword == "" {
		return fmt.Errorf("obfs password required for salamander")
	}
	if cfg.MinHopIntervalSec > 0 && cfg.MaxHopIntervalSec > 0 &&
		cfg.MinHopIntervalSec > cfg.MaxHopIntervalSec {
		return fmt.Errorf("min_hop_interval (%ds) exceeds max_hop_interval (%ds)",
			cfg.MinHopIntervalSec, cfg.MaxHopIntervalSec)
	}
	if err := validateQUICBounds(&cfg); err != nil {
		return err
	}
	return nil
}

func validateQUICBounds(cfg *clientConfig) error {
	const minWindow = 16384
	windows := []struct {
		name  string
		value uint64
	}{
		{"init_stream_receive_window", cfg.InitStreamReceiveWindow},
		{"max_stream_receive_window", cfg.MaxStreamReceiveWindow},
		{"init_conn_receive_window", cfg.InitConnReceiveWindow},
		{"max_conn_receive_window", cfg.MaxConnReceiveWindow},
	}
	for _, w := range windows {
		if w.value != 0 && w.value < minWindow {
			return fmt.Errorf("%s (%d) must be at least %d", w.name, w.value, minWindow)
		}
	}
	if cfg.MaxIdleTimeoutSec != 0 && (cfg.MaxIdleTimeoutSec < 4 || cfg.MaxIdleTimeoutSec > 120) {
		return fmt.Errorf("max_idle_timeout (%ds) must be between 4 and 120 seconds", cfg.MaxIdleTimeoutSec)
	}
	if cfg.KeepAlivePeriodSec != 0 && (cfg.KeepAlivePeriodSec < 2 || cfg.KeepAlivePeriodSec > 60) {
		return fmt.Errorf("keep_alive_period (%ds) must be between 2 and 60 seconds", cfg.KeepAlivePeriodSec)
	}
	return nil
}

func (s *Session) Close() error {
	if !s.closed.CompareAndSwap(false, true) {
		return nil
	}

	log(LogLevelInfo, srcTunnel, "Closing session...")

	_ = s.StopTUN()

	s.clientMu.Lock()
	c := s.client
	s.client = nil
	s.clientMu.Unlock()

	s.closeAllActiveConns()
	s.dnsCache.clear()

	if c == nil {
		log(LogLevelInfo, srcTunnel, "Session closed")
		return nil
	}

	done := make(chan error, 1)
	go func() { done <- c.Close() }()

	var err error
	select {
	case err = <-done:
	case <-time.After(3 * time.Second):
		err = fmt.Errorf("client close timed out")
		log(LogLevelWarn, srcTunnel, "Client close timed out; continuing")
	}

	log(LogLevelInfo, srcTunnel, "Session closed (tx=%dB rx=%dB)",
		s.txBytes.Load(), s.rxBytes.Load())
	return err
}

func (s *Session) ResetConnections() {
	log(LogLevelInfo, srcTunnel, "Resetting upstream connections")
	s.closeAllActiveConns()
	s.dnsCache.clear()
}

func (s *Session) GetTxBytes() int64 { return s.txBytes.Load() }

func (s *Session) GetRxBytes() int64 { return s.rxBytes.Load() }

func (s *Session) addTx(n int) {
	if n > 0 {
		s.txBytes.Add(int64(n))
	}
}

func (s *Session) addRx(n int) {
	if n > 0 {
		s.rxBytes.Add(int64(n))
	}
}

func (s *Session) currentClient() *reconnectClient {
	s.clientMu.Lock()
	defer s.clientMu.Unlock()
	return s.client
}

func (s *Session) protectPacketConn(conn net.PacketConn) {
	p := s.protector
	if p == nil {
		return
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		return
	}
	rawConn, err := udpConn.SyscallConn()
	if err != nil {
		log(LogLevelWarn, srcTransport, "Could not access raw socket for protect(): %s", err)
		return
	}
	var protected bool
	var seenFd int32
	_ = rawConn.Control(func(fd uintptr) {
		seenFd = int32(fd)
		protected = p.Protect(seenFd)
	})
	if !protected {
		log(LogLevelWarn, srcTransport,
			"VpnService.protect(fd=%d) returned false — traffic may loop through VPN",
			seenFd)
	}
}

func (s *Session) registerActiveConn(c net.PacketConn) {
	s.activeConnsMu.Lock()
	s.activeConns[c] = struct{}{}
	s.activeConnsMu.Unlock()
}

func (s *Session) unregisterActiveConn(c net.PacketConn) {
	s.activeConnsMu.Lock()
	delete(s.activeConns, c)
	s.activeConnsMu.Unlock()
}

func (s *Session) closeAllActiveConns() {
	s.activeConnsMu.Lock()
	conns := make([]net.PacketConn, 0, len(s.activeConns))
	for c := range s.activeConns {
		conns = append(conns, c)
	}
	s.activeConns = map[net.PacketConn]struct{}{}
	s.activeConnsMu.Unlock()
	for _, c := range conns {
		_ = c.Close()
	}
}

