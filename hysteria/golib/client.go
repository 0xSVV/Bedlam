package golib

import (
	"encoding/json"
	"fmt"
	"net"
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

func TestUDP() string {
	clientMu.Lock()
	c := activeClient
	clientMu.Unlock()

	if c == nil {
		return "error: client not connected"
	}

	rc, err := c.UDP()
	if err != nil {
		return fmt.Sprintf("error: UDP session failed: %s", err)
	}
	defer rc.Close()

	dnsQuery := buildDNSQuery()

	logMsg(LogLevelInfo, "TestUDP: sending DNS query to 8.8.8.8:53 via QUIC datagram")
	if err := rc.Send(dnsQuery, "8.8.8.8:53"); err != nil {
		return fmt.Sprintf("error: send failed: %s", err)
	}

	type result struct {
		data []byte
		from string
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		data, from, err := rc.Receive()
		ch <- result{data, from, err}
	}()

	select {
	case r := <-ch:
		if r.err != nil {
			return fmt.Sprintf("error: receive failed: %s", r.err)
		}
		return fmt.Sprintf("ok: %d bytes from %s (UDP works)", len(r.data), r.from)
	case <-time.After(10 * time.Second):
		return "error: timeout (VPS likely blocks outbound UDP)"
	}
}

func TestDNSOverTCP() string {
	clientMu.Lock()
	c := activeClient
	clientMu.Unlock()

	if c == nil {
		return "error: client not connected"
	}

	logMsg(LogLevelInfo, "TestDNS: sending DNS query to 8.8.8.8:53 via TCP")
	resp, err := dnsOverTCP(c, "8.8.8.8:53", buildDNSQuery())
	if err != nil {
		return fmt.Sprintf("error: %s", err)
	}
	return fmt.Sprintf("ok: %d bytes (DNS-over-TCP works)", len(resp))
}

func buildDNSQuery() []byte {
	return []byte{
		0x12, 0x34, // Transaction ID
		0x01, 0x00, // Flags: standard query, recursion desired
		0x00, 0x01, // Questions: 1
		0x00, 0x00, // Answers: 0
		0x00, 0x00, // Authority: 0
		0x00, 0x00, // Additional: 0
		// Query: example.com
		0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
		0x03, 'c', 'o', 'm',
		0x00,       // Root label
		0x00, 0x01, // Type A
		0x00, 0x01, // Class IN
	}
}
