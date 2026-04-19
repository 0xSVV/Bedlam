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
	OnDisconnected(reason string)
	OnError(message string)
}

var (
	activeClient  client.Client
	clientMutex   sync.Mutex
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

	client, err := client.NewReconnectableClient(
		func() (*client.Config, error) {
			return buildCoreConfig(&cfg)
		},
		func(_ client.Client, info *client.HandshakeInfo, count int) {
			log(LogLevelInfo, "Connected (UDP: %v, TX: %d, count: %d)", info.UDPEnabled, info.Tx, count)
			if handler != nil {
				handler.OnConnected(info.UDPEnabled)
			}
		},
		cfg.Lazy,
	)
	if err != nil {
		log(LogLevelError, "Connection failed: %s", err.Error())
		return fmt.Errorf("connect: %w", err)
	}
	activeClient = client
	return nil
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
