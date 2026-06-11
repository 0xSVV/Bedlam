package golib

import (
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
)

type reconnectClient struct {
	configFunc func() (*client.Config, error)
	handler    EventHandler
	statsFunc  func() (tx, rx int64)

	mu      sync.Mutex
	inner   client.Client
	closed  bool
	attempt atomic.Int32

	dialMu       sync.Mutex
	stopWatchdog chan struct{}
	watchdogDone chan struct{}

	lastTx int64
	lastRx int64
}

func newReconnectClient(
	cf func() (*client.Config, error),
	h EventHandler,
	statsFunc func() (tx, rx int64),
) (*reconnectClient, error) {
	rc := &reconnectClient{
		configFunc:   cf,
		handler:      h,
		statsFunc:    statsFunc,
		stopWatchdog: make(chan struct{}),
		watchdogDone: make(chan struct{}),
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
	if rc.closed {
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
	if rc.closed {
		rc.mu.Unlock()
		_ = cli.Close()
		return coreErrs.ClosedError{}
	}
	rc.inner = cli
	prevAttempt := rc.attempt.Swap(0)
	rc.mu.Unlock()
	if rc.handler != nil {
		rc.handler.OnConnected(info.UDPEnabled, prevAttempt)
	}
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
	rc.mu.Unlock()
	attempt := rc.attempt.Add(1)
	_ = old.Close()
	if rc.handler != nil {
		rc.handler.OnReconnecting(attempt, fmt.Sprintf("[%s] %s", source, err.Error()))
	}
}

func (rc *reconnectClient) currentClient(callerSource string) (client.Client, error) {
	rc.mu.Lock()
	if rc.closed {
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
		attempt := rc.attempt.Add(1)
		if rc.handler != nil {
			rc.handler.OnReconnecting(attempt, fmt.Sprintf("[%s] dial: %s", callerSource, err.Error()))
		}
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
	conn, err := c.TCP(addr)
	if isReconnectable(err) {
		rc.markDead(err, srcStream)
	}
	return conn, err
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
	if rc.closed {
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
