package golib

import (
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

const watchdogInterval = 60 * time.Second

type reconnectClient struct {
	configFunc func() (*client.Config, error)
	handler    EventHandler

	mu      sync.Mutex
	inner   client.Client
	closed  bool
	attempt int32

	stopWatchdog chan struct{}
}

func newReconnectClient(cf func() (*client.Config, error), h EventHandler) (*reconnectClient, error) {
	rc := &reconnectClient{
		configFunc:   cf,
		handler:      h,
		stopWatchdog: make(chan struct{}),
	}
	if err := rc.dial(); err != nil {
		return nil, err
	}
	go rc.watchdog()
	return rc, nil
}

func (rc *reconnectClient) dial() error {
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
	rc.attempt = 0
	rc.mu.Unlock()
	if rc.handler != nil {
		rc.handler.OnConnected(info.UDPEnabled)
	}
	return nil
}

func (rc *reconnectClient) markDead(err error) {
	rc.mu.Lock()
	if rc.closed || rc.inner == nil {
		rc.mu.Unlock()
		return
	}
	old := rc.inner
	rc.inner = nil
	rc.attempt++
	attempt := rc.attempt
	rc.mu.Unlock()
	_ = old.Close()
	if rc.handler != nil {
		rc.handler.OnReconnecting(attempt, err.Error())
	}
}

func (rc *reconnectClient) currentClient() (client.Client, error) {
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
		if rc.handler != nil {
			rc.mu.Lock()
			rc.attempt++
			attempt := rc.attempt
			rc.mu.Unlock()
			rc.handler.OnReconnecting(attempt, err.Error())
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
	c, err := rc.currentClient()
	if err != nil {
		return nil, err
	}
	conn, err := c.TCP(addr)
	if isReconnectable(err) {
		rc.markDead(err)
	}
	return conn, err
}

func (rc *reconnectClient) UDP() (client.HyUDPConn, error) {
	c, err := rc.currentClient()
	if err != nil {
		return nil, err
	}
	udp, err := c.UDP()
	if isReconnectable(err) {
		rc.markDead(err)
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
	if inner != nil {
		return inner.Close()
	}
	return nil
}

func (rc *reconnectClient) watchdog() {
	ticker := time.NewTicker(watchdogInterval)
	defer ticker.Stop()
	for {
		select {
		case <-rc.stopWatchdog:
			return
		case <-ticker.C:
			rc.probe()
		}
	}
}

func (rc *reconnectClient) probe() {
	rc.mu.Lock()
	if rc.closed {
		rc.mu.Unlock()
		return
	}
	c := rc.inner
	rc.mu.Unlock()
	if c == nil {
		return
	}
	udp, err := c.UDP()
	if isReconnectable(err) {
		rc.markDead(err)
		return
	}
	if udp != nil {
		_ = udp.Close()
	}
}

func isReconnectable(err error) bool {
	if err == nil {
		return false
	}
	if _, ok := err.(coreErrs.DialError); ok {
		return false
	}
	return true
}
