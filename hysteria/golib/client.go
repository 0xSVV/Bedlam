package golib

import (
	"encoding/json"
	"fmt"
	"net"
	"sync"

	"github.com/apernet/hysteria/core/v2/client"
)

type EventHandler interface {
	OnConnected(udpEnabled bool)
	OnDisconnected(reason string)
	OnError(message string)
}

var (
	clientMu      sync.Mutex
	activeClient  client.Client
	socksListener net.Listener
	httpListener  net.Listener
)

func StartClient(configJSON string, handler EventHandler) error {
	clientMu.Lock()
	defer clientMu.Unlock()

	if activeClient != nil {
		return fmt.Errorf("client already running")
	}

	var cfg clientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid config JSON: %w", err)
	}

	logMsg(LogLevelInfo, "Starting client for %s", cfg.Server)

	c, err := client.NewReconnectableClient(
		func() (*client.Config, error) {
			return buildCoreConfig(&cfg)
		},
		func(_ client.Client, info *client.HandshakeInfo, count int) {
			logMsg(LogLevelInfo, "Connected (UDP: %v, TX: %d, count: %d)", info.UDPEnabled, info.Tx, count)
			if handler != nil {
				handler.OnConnected(info.UDPEnabled)
			}
		},
		cfg.Lazy,
	)
	if err != nil {
		logMsg(LogLevelError, "Connection failed: %s", err.Error())
		return fmt.Errorf("connect: %w", err)
	}
	activeClient = c

	if cfg.SocksAddr != "" {
		if err := startSOCKS5(c, cfg.SocksAddr, socksProxyConfig{
			Username:   cfg.SocksUsername,
			Password:   cfg.SocksPassword,
			DisableUDP: cfg.SocksDisableUDP,
		}); err != nil {
			stopClientLocked()
			return fmt.Errorf("socks5: %w", err)
		}
		logMsg(LogLevelInfo, "SOCKS5 listening on %s", cfg.SocksAddr)
	}

	if cfg.HttpAddr != "" {
		if err := startHTTPProxy(c, cfg.HttpAddr, httpProxyConfig{
			Username: cfg.HttpUsername,
			Password: cfg.HttpPassword,
		}); err != nil {
			stopClientLocked()
			return fmt.Errorf("http proxy: %w", err)
		}
		logMsg(LogLevelInfo, "HTTP proxy listening on %s", cfg.HttpAddr)
	}

	return nil
}

func StopClient() error {
	clientMu.Lock()
	defer clientMu.Unlock()
	return stopClientLocked()
}

func stopClientLocked() error {
	if activeClient == nil {
		return fmt.Errorf("no client running")
	}

	logMsg(LogLevelInfo, "Stopping client...")

	if socksListener != nil {
		socksListener.Close()
		socksListener = nil
	}
	if httpListener != nil {
		httpListener.Close()
		httpListener = nil
	}

	err := activeClient.Close()
	activeClient = nil

	logMsg(LogLevelInfo, "Client stopped")
	return err
}

func IsRunning() bool {
	clientMu.Lock()
	defer clientMu.Unlock()
	return activeClient != nil
}
