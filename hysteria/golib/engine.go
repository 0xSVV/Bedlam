package golib

import (
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net"
	"sync"

	"github.com/apernet/hysteria/core/v2/client"
)

type ClientConfig struct {
	Server                  string `json:"server"`
	Auth                    string `json:"auth"`
	TLSSni                  string `json:"tls_sni"`
	TLSInsecure             bool   `json:"tls_insecure"`
	TLSCA                   string `json:"tls_ca"`
	DisablePathMTUDiscovery bool   `json:"disable_pmtud"`
	FastOpen                bool   `json:"fast_open"`
	MaxTxMbps               int    `json:"max_tx_mbps"`
	MaxRxMbps               int    `json:"max_rx_mbps"`
}

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

func StartClient(configJSON string, socksAddr string, httpAddr string, handler EventHandler) error {
	clientMu.Lock()
	defer clientMu.Unlock()

	if activeClient != nil {
		return fmt.Errorf("client already running")
	}

	var cfg ClientConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid config JSON: %w", err)
	}

	logMsg(LogLevelInfo, "Resolving server address: %s", cfg.Server)
	serverAddr, err := resolveServerAddr(cfg.Server)
	if err != nil {
		return fmt.Errorf("resolve server: %w", err)
	}
	logMsg(LogLevelInfo, "Resolved to %s", serverAddr.String())

	coreConfig := &client.Config{
		ServerAddr: serverAddr,
		Auth:       cfg.Auth,
		FastOpen:   cfg.FastOpen,
		QUICConfig: client.QUICConfig{
			DisablePathMTUDiscovery: cfg.DisablePathMTUDiscovery,
		},
	}

	if cfg.TLSSni != "" {
		coreConfig.TLSConfig.ServerName = cfg.TLSSni
		logMsg(LogLevelDebug, "TLS SNI: %s", cfg.TLSSni)
	}
	coreConfig.TLSConfig.InsecureSkipVerify = cfg.TLSInsecure

	if cfg.TLSCA != "" {
		pool := x509.NewCertPool()
		block, _ := pem.Decode([]byte(cfg.TLSCA))
		if block != nil {
			cert, err := x509.ParseCertificate(block.Bytes)
			if err != nil {
				return fmt.Errorf("parse CA cert: %w", err)
			}
			pool.AddCert(cert)
		} else if !pool.AppendCertsFromPEM([]byte(cfg.TLSCA)) {
			return fmt.Errorf("failed to parse CA PEM")
		}
		coreConfig.TLSConfig.RootCAs = pool
	}

	if cfg.MaxTxMbps > 0 {
		coreConfig.BandwidthConfig.MaxTx = uint64(cfg.MaxTxMbps) * 125000
	}
	if cfg.MaxRxMbps > 0 {
		coreConfig.BandwidthConfig.MaxRx = uint64(cfg.MaxRxMbps) * 125000
	}

	if cfg.DisablePathMTUDiscovery {
		logMsg(LogLevelDebug, "Path MTU Discovery disabled")
	}

	logMsg(LogLevelInfo, "Connecting to %s...", serverAddr.String())
	c, info, err := client.NewClient(coreConfig)
	if err != nil {
		logMsg(LogLevelError, "Connection failed: %s", err.Error())
		return fmt.Errorf("connect: %w", err)
	}
	activeClient = c

	logMsg(LogLevelInfo, "Connected (UDP: %v, TX: %d)", info.UDPEnabled, info.Tx)

	if handler != nil {
		handler.OnConnected(info.UDPEnabled)
	}

	if socksAddr != "" {
		if err := startSOCKS5(c, socksAddr); err != nil {
			c.Close()
			activeClient = nil
			return fmt.Errorf("socks5: %w", err)
		}
		logMsg(LogLevelInfo, "SOCKS5 listening on %s", socksAddr)
	}

	if httpAddr != "" {
		if err := startHTTPProxy(c, httpAddr); err != nil {
			c.Close()
			activeClient = nil
			return fmt.Errorf("http proxy: %w", err)
		}
		logMsg(LogLevelInfo, "HTTP proxy listening on %s", httpAddr)
	}

	return nil
}

func StopClient() error {
	clientMu.Lock()
	defer clientMu.Unlock()

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

func resolveServerAddr(server string) (*net.UDPAddr, error) {
	host, port, err := net.SplitHostPort(server)
	if err != nil {
		return nil, fmt.Errorf("invalid server address %q: %w", server, err)
	}
	ips, err := net.LookupHost(host)
	if err != nil {
		return nil, fmt.Errorf("DNS lookup failed for %q: %w", host, err)
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no addresses found for %q", host)
	}
	portNum, err := net.LookupPort("udp", port)
	if err != nil {
		return nil, err
	}
	return &net.UDPAddr{
		IP:   net.ParseIP(ips[0]),
		Port: portNum,
	}, nil
}
