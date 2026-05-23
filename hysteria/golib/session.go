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

	resolved, err := resolveHost(cfg.Server)
	if err != nil {
		return nil, fmt.Errorf("resolve server: %w", err)
	}
	log(LogLevelInfo, srcTunnel, "Resolved server address: %s", resolved.String())

	s := &Session{
		protector:   protector,
		activeConns: map[net.PacketConn]struct{}{},
		dnsCache:    newDNSCache(),
	}

	wrappedHandler := &loggingHandler{inner: handler}
	rc, err := newReconnectClient(
		func() (*client.Config, error) {
			return buildCoreConfig(&cfg, resolved, s)
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

