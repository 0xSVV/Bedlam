package golib

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

type EventHandler interface {
	OnConnected(udpEnabled bool)
	OnReconnecting(attempt int32, reason string)
	OnError(message string)
}

var (
	activeClient client.Client
	clientMutex  sync.Mutex
)

func StartClient(configJSON string, handler EventHandler) error {
	clientMutex.Lock()
	defer clientMutex.Unlock()

	if activeClient != nil {
		return fmt.Errorf("client already running")
	}

	var cfg clientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid config JSON: %w", err)
	}

	log(LogLevelInfo, "Starting client for %s", cfg.Server)
	resetStats()

	resolved, err := resolveHost(cfg.Server)
	if err != nil {
		return fmt.Errorf("resolve server: %w", err)
	}
	log(LogLevelInfo, "Resolved server address: %s", resolved.String())

	wrappedHandler := &loggingHandler{inner: handler}
	rc, err := newReconnectClient(
		func() (*client.Config, error) {
			return buildCoreConfig(&cfg, resolved)
		},
		wrappedHandler,
	)
	if err != nil {
		log(LogLevelError, "Connection failed: %s", err.Error())
		if handler != nil {
			handler.OnError(err.Error())
		}
		return fmt.Errorf("connect: %w", err)
	}
	activeClient = rc
	return nil
}

type loggingHandler struct {
	inner EventHandler
}

func (h *loggingHandler) OnConnected(udpEnabled bool) {
	log(LogLevelInfo, "Connected (UDP: %v)", udpEnabled)
	if h.inner != nil {
		h.inner.OnConnected(udpEnabled)
	}
}

func (h *loggingHandler) OnReconnecting(attempt int32, reason string) {
	log(LogLevelWarn, "Reconnecting (attempt %d): %s", attempt, reason)
	if h.inner != nil {
		h.inner.OnReconnecting(attempt, reason)
	}
}

func (h *loggingHandler) OnError(message string) {
	log(LogLevelError, "Tunnel error: %s", message)
	if h.inner != nil {
		h.inner.OnError(message)
	}
}

func StopClient() error {
	clientMutex.Lock()
	defer clientMutex.Unlock()
	return stopClientLocked()
}

func stopClientLocked() error {
	if activeClient == nil {
		return fmt.Errorf("no client running")
	}

	log(LogLevelInfo, "Stopping client...")

	closeAllActiveConns()

	c := activeClient
	activeClient = nil
	globalDNSCache.clear()

	done := make(chan error, 1)
	go func() { done <- c.Close() }()

	var err error
	select {
	case err = <-done:
	case <-time.After(3 * time.Second):
		err = fmt.Errorf("client close timed out")
		log(LogLevelWarn, "Client close timed out; continuing")
	}

	log(LogLevelInfo, "Client stopped")
	return err
}

func ResetConnections() {
	log(LogLevelInfo, "Resetting upstream connections")
	closeAllActiveConns()
	globalDNSCache.clear()
}

func IsRunning() bool {
	clientMutex.Lock()
	defer clientMutex.Unlock()
	return activeClient != nil
}
