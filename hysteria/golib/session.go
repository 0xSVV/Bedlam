package golib

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
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

	protectorMu sync.Mutex
	protector   FdProtector

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

	log(LogLevelInfo, "Starting client for %s", cfg.Server)

	resolved, err := resolveHost(cfg.Server)
	if err != nil {
		return nil, fmt.Errorf("resolve server: %w", err)
	}
	log(LogLevelInfo, "Resolved server address: %s", resolved.String())

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
		log(LogLevelError, "Connection failed: %s", err.Error())
		if handler != nil {
			handler.OnError(err.Error())
		}
		return nil, fmt.Errorf("connect: %w", err)
	}
	s.client = rc
	return s, nil
}

func (s *Session) Close() error {
	if !s.closed.CompareAndSwap(false, true) {
		return nil
	}

	log(LogLevelInfo, "Closing session...")

	_ = s.StopTUN()

	s.clientMu.Lock()
	c := s.client
	s.client = nil
	s.clientMu.Unlock()

	s.closeAllActiveConns()
	s.dnsCache.clear()

	if c == nil {
		return nil
	}

	done := make(chan error, 1)
	go func() { done <- c.Close() }()

	var err error
	select {
	case err = <-done:
	case <-time.After(3 * time.Second):
		err = fmt.Errorf("client close timed out")
		log(LogLevelWarn, "Client close timed out; continuing")
	}

	log(LogLevelInfo, "Session closed")
	return err
}

func (s *Session) ResetConnections() {
	log(LogLevelInfo, "Resetting upstream connections")
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

func (s *Session) getProtector() FdProtector {
	s.protectorMu.Lock()
	defer s.protectorMu.Unlock()
	return s.protector
}

func (s *Session) protectPacketConn(conn net.PacketConn) {
	p := s.getProtector()
	if p == nil {
		return
	}
	if udpConn, ok := conn.(*net.UDPConn); ok {
		if rawConn, err := udpConn.SyscallConn(); err == nil {
			_ = rawConn.Control(func(fd uintptr) {
				p.Protect(int32(fd))
			})
		}
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
