package golib

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

const (
	watchdogInterval = 60 * time.Second
	probeDNSServer   = "1.1.1.1:53"

	tcpDialTimeout          = 10 * time.Second
	maxConsecutiveTCPStalls = 3
)

var (
	errConnectionsReset = errors.New("connections reset")
	errDialTimeout      = errors.New("dial timed out")
)

type reconnectClient struct {
	configFunc    func() (*client.Config, error)
	handler       EventHandler
	statsFunc     func() (tx, rx int64)
	echConfigured bool

	mu         sync.Mutex
	inner      client.Client
	closed     bool
	fatal      atomic.Bool
	attempt    atomic.Int32
	generation uint64 // guarded by mu; bumped on every state transition

	eventMu        sync.Mutex
	lastEmittedGen uint64 // guarded by eventMu

	dialMu       sync.Mutex
	stopWatchdog chan struct{}
	watchdogDone chan struct{}

	tcpStalls atomic.Int32

	lastTx int64
	lastRx int64
}

func (rc *reconnectClient) nextGen() uint64 {
	rc.mu.Lock()
	rc.generation++
	g := rc.generation
	rc.mu.Unlock()
	return g
}

func (rc *reconnectClient) emit(gen uint64, fn func()) {
	rc.eventMu.Lock()
	defer rc.eventMu.Unlock()
	if gen <= rc.lastEmittedGen {
		return
	}
	rc.lastEmittedGen = gen
	if rc.handler != nil {
		fn()
	}
}

func newReconnectClient(
	cf func() (*client.Config, error),
	h EventHandler,
	statsFunc func() (tx, rx int64),
	echConfigured bool,
) (*reconnectClient, error) {
	rc := &reconnectClient{
		configFunc:    cf,
		handler:       h,
		statsFunc:     statsFunc,
		echConfigured: echConfigured,
		stopWatchdog:  make(chan struct{}),
		watchdogDone:  make(chan struct{}),
	}
	if err := rc.dial(); err != nil {
		close(rc.watchdogDone)
		return nil, err
	}
	go rc.watchdog()
	return rc, nil
}

func (rc *reconnectClient) dial() error {
	rc.dialMu.Lock()
	defer rc.dialMu.Unlock()

	rc.mu.Lock()
	if rc.closed || rc.fatal.Load() {
		rc.mu.Unlock()
		return coreErrs.ClosedError{}
	}
	if rc.inner != nil {
		rc.mu.Unlock()
		return nil
	}
	rc.mu.Unlock()

	cfg, err := rc.configFunc()
	if err != nil {
		return err
	}
	cli, info, err := client.NewClient(cfg)
	if err != nil {
		return err
	}
	rc.mu.Lock()
	if rc.closed || rc.fatal.Load() {
		rc.mu.Unlock()
		_ = cli.Close()
		return coreErrs.ClosedError{}
	}
	rc.inner = cli
	prevAttempt := rc.attempt.Swap(0)
	rc.generation++
	gen := rc.generation
	rc.mu.Unlock()
	rc.emit(gen, func() {
		rc.handler.OnConnected(info.UDPEnabled, prevAttempt)
	})
	return nil
}

func (rc *reconnectClient) markDead(err error, source string) {
	rc.mu.Lock()
	if rc.closed || rc.inner == nil {
		rc.mu.Unlock()
		return
	}
	old := rc.inner
	rc.inner = nil
	rc.generation++
	gen := rc.generation
	rc.mu.Unlock()
	attempt := rc.attempt.Add(1)
	_ = old.Close()
	rc.emit(gen, func() {
		rc.handler.OnReconnecting(attempt, fmt.Sprintf("[%s] %s", source, err.Error()))
	})
}

func (rc *reconnectClient) reset() {
	rc.markDead(errConnectionsReset, srcReset)
	if _, err := rc.currentClient(srcReset); err != nil {
		log(LogLevelDebug, srcReset, "Re-dial after reset failed: %s", err)
	}
}

func (rc *reconnectClient) failTerminal(err error) {
	if !rc.fatal.CompareAndSwap(false, true) {
		return
	}
	gen := rc.nextGen()
	rc.emit(gen, func() {
		rc.handler.OnDisconnected(err.Error())
	})
}

func (rc *reconnectClient) currentClient(callerSource string) (client.Client, error) {
	rc.mu.Lock()
	if rc.closed || rc.fatal.Load() {
		rc.mu.Unlock()
		return nil, coreErrs.ClosedError{}
	}
	if rc.inner != nil {
		c := rc.inner
		rc.mu.Unlock()
		return c, nil
	}
	rc.mu.Unlock()
	if err := rc.dial(); err != nil {
		if rc.isTerminal(err) {
			rc.failTerminal(err)
			return nil, err
		}
		gen := rc.nextGen()
		attempt := rc.attempt.Add(1)
		rc.emit(gen, func() {
			rc.handler.OnReconnecting(attempt, fmt.Sprintf("[%s] dial: %s", callerSource, err.Error()))
		})
		return nil, err
	}
	rc.mu.Lock()
	c := rc.inner
	rc.mu.Unlock()
	if c == nil {
		return nil, coreErrs.ClosedError{}
	}
	return c, nil
}

func (rc *reconnectClient) TCP(addr string) (net.Conn, error) {
	c, err := rc.currentClient(srcStream)
	if err != nil {
		return nil, err
	}
	conn, err := dialTCPWithTimeout(c, addr, tcpDialTimeout)
	switch {
	case err == nil:
		rc.tcpStalls.Store(0)
	case errors.Is(err, errDialTimeout):
		if rc.tcpStalls.Add(1) >= maxConsecutiveTCPStalls {
			rc.tcpStalls.Store(0)
			rc.markDead(fmt.Errorf("%d consecutive TCP dials stalled", maxConsecutiveTCPStalls), srcStream)
		}
	case isReconnectable(err):
		rc.markDead(err, srcStream)
	}
	return conn, err
}

func dialTCPWithTimeout(c client.Client, addr string, timeout time.Duration) (net.Conn, error) {
	type result struct {
		conn net.Conn
		err  error
	}
	done := make(chan result, 1)
	go func() {
		conn, err := c.TCP(addr)
		done <- result{conn, err}
	}()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case r := <-done:
		return r.conn, r.err
	case <-timer.C:
		go func() {
			if r := <-done; r.conn != nil {
				_ = r.conn.Close()
			}
		}()
		return nil, fmt.Errorf("TCP dial to %s: %w", addr, errDialTimeout)
	}
}

func (rc *reconnectClient) UDP() (client.HyUDPConn, error) {
	c, err := rc.currentClient(srcStream)
	if err != nil {
		return nil, err
	}
	udp, err := c.UDP()
	if isReconnectable(err) {
		rc.markDead(err, srcStream)
	}
	return udp, err
}

func (rc *reconnectClient) Close() error {
	rc.mu.Lock()
	if rc.closed {
		rc.mu.Unlock()
		return nil
	}
	rc.closed = true
	inner := rc.inner
	rc.inner = nil
	rc.mu.Unlock()

	close(rc.stopWatchdog)
	<-rc.watchdogDone

	if inner != nil {
		return inner.Close()
	}
	return nil
}

func (rc *reconnectClient) watchdog() {
	defer close(rc.watchdogDone)
	ticker := time.NewTicker(watchdogInterval)
	defer ticker.Stop()
	for {
		select {
		case <-rc.stopWatchdog:
			return
		case <-ticker.C:
			rc.tick()
		}
	}
}

func (rc *reconnectClient) tick() {
	rc.mu.Lock()
	if rc.closed || rc.fatal.Load() {
		rc.mu.Unlock()
		return
	}
	c := rc.inner
	rc.mu.Unlock()

	if c == nil {
		log(LogLevelDebug, srcWatchdog, "Down and idle; re-dialing")
		if _, err := rc.currentClient(srcWatchdog); err != nil {
			log(LogLevelDebug, srcWatchdog, "Idle re-dial failed: %s", err)
		}
		return
	}

	udp, err := c.UDP()
	if isReconnectable(err) {
		rc.markDead(err, srcWatchdog)
		return
	}
	if udp != nil {
		_ = udp.Close()
	}

	if rc.statsFunc == nil {
		return
	}
	tx, rx := rc.statsFunc()
	stalled := tx > rc.lastTx && rx == rc.lastRx
	rc.lastTx, rc.lastRx = tx, rx
	if stalled {
		rc.probe(c)
	}
}

// Outbound traffic with no return traffic for a full watchdog interval
// suggests a black-holed path that QUIC hasn't noticed yet. A round trip
// through the tunnel settles it.
func (rc *reconnectClient) probe(c client.Client) {
	log(LogLevelDebug, srcWatchdog, "Traffic stalled; probing tunnel")
	_, err := dnsOverTCP(c, probeDNSServer, buildDNSQuery())
	if isReconnectable(err) {
		rc.markDead(fmt.Errorf("stall probe failed: %w", err), srcWatchdog)
	}
}

func isReconnectable(err error) bool {
	if err == nil {
		return false
	}
	var dialErr coreErrs.DialError
	if errors.As(err, &dialErr) {
		return false
	}
	return true
}

func (rc *reconnectClient) isTerminal(err error) bool {
	if err == nil {
		return false
	}
	var authErr coreErrs.AuthError
	if errors.As(err, &authErr) {
		return true
	}
	var cfgErr coreErrs.ConfigError
	if errors.As(err, &cfgErr) {
		return true
	}
	var echErr *tls.ECHRejectionError
	if errors.As(err, &echErr) {
		return true
	}
	// When ECH is rejected, crypto/tls ignores InsecureSkipVerify and skips
	// VerifyPeerCertificate, so self-signed/pinned setups surface a stale ECH
	// config as a certificate verification failure instead of
	// ECHRejectionError. Retrying cannot succeed in either case.
	if rc.echConfigured {
		var certErr *tls.CertificateVerificationError
		if errors.As(err, &certErr) {
			return true
		}
	}
	return false
}
