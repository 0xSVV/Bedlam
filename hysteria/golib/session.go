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
	"github.com/apernet/hysteria/extras/v2/realm"
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
	var configFunc func() (*client.Config, error)
	if isRealmAddr(cfg.Server) {
		configFunc = func() (*client.Config, error) {
			return dialRealm(&cfg, s)
		}
	} else {
		var lastResolved net.Addr
		configFunc = func() (*client.Config, error) {
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
		}
	}
	rc, err := newReconnectClient(
		configFunc,
		wrappedHandler,
		func() (int64, int64) {
			return s.txBytes.Load(), s.rxBytes.Load()
		},
		cfg.TLSECH != "",
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
	if isRealmAddr(cfg.Server) {
		if _, err := realm.ParseAddr(cfg.Server); err != nil {
			return fmt.Errorf("invalid realm address %q: %w", cfg.Server, err)
		}
	} else if _, _, err := net.SplitHostPort(cfg.Server); err != nil {
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
	if cfg.TLSECH != "" {
		if _, err := parseECHConfigList(cfg.TLSECH); err != nil {
			return fmt.Errorf("invalid ECH config list: %w", err)
		}
	}
	if err := validateObfs(&cfg); err != nil {
		return err
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

func validateObfs(cfg *clientConfig) error {
	obfsType := strings.ToLower(cfg.ObfsType)
	switch obfsType {
	case "", "plain":
		return nil
	case "salamander":
		if cfg.ObfsPassword == "" {
			return fmt.Errorf("obfs password required for salamander")
		}
		return nil
	case "gecko":
		if cfg.ObfsPassword == "" {
			return fmt.Errorf("obfs password required for gecko")
		}
		return validateGeckoSizes(cfg.ObfsGeckoMinPacketSize, cfg.ObfsGeckoMaxPacketSize)
	default:
		return fmt.Errorf("unsupported obfs type %q", cfg.ObfsType)
	}
}

// Mirrors the bounds enforced by obfs.WrapPacketConnGecko: zero means the
// upstream default (512/1200), the hard ceiling is its 2048-byte buffer.
func validateGeckoSizes(minPkt, maxPkt int) error {
	const (
		defaultMin = 512
		defaultMax = 1200
		ceiling    = 2048
	)
	if minPkt == 0 {
		minPkt = defaultMin
	}
	if maxPkt == 0 {
		maxPkt = defaultMax
	}
	if minPkt <= 0 || minPkt > maxPkt || maxPkt > ceiling {
		return fmt.Errorf("gecko packet size must satisfy 0 < min (%d) <= max (%d) <= %d",
			minPkt, maxPkt, ceiling)
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
	if c := s.currentClient(); c != nil {
		c.reset()
	}
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

func (s *Session) protectPacketConn(conn net.PacketConn) error {
	p := s.protector
	if p == nil {
		return nil
	}
	udpConn, ok := conn.(*net.UDPConn)
	if !ok {
		return nil
	}
	rawConn, err := udpConn.SyscallConn()
	if err != nil {
		return fmt.Errorf("access raw socket for protect(): %w", err)
	}
	var protected bool
	var seenFd int32
	_ = rawConn.Control(func(fd uintptr) {
		seenFd = int32(fd)
		protected = p.Protect(seenFd)
	})
	if !protected {
		return fmt.Errorf("VpnService.protect(fd=%d) returned false", seenFd)
	}
	return nil
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
