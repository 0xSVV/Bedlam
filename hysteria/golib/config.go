package golib

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/extras/v2/obfs"
	"github.com/apernet/hysteria/extras/v2/transport/udphop"
)

type clientConfig struct {
	Server string `json:"server"`
	Auth   string `json:"auth"`

	TLSSni        string `json:"tls_sni"`
	TLSInsecure   bool   `json:"tls_insecure"`
	TLSPinSHA256  string `json:"tls_pin_sha256"`
	TLSCA         string `json:"tls_ca"`
	TLSClientCert string `json:"tls_client_cert"`
	TLSClientKey  string `json:"tls_client_key"`

	ObfsType     string `json:"obfs_type"`
	ObfsPassword string `json:"obfs_password"`

	InitStreamReceiveWindow uint64 `json:"init_stream_receive_window"`
	MaxStreamReceiveWindow  uint64 `json:"max_stream_receive_window"`
	InitConnReceiveWindow   uint64 `json:"init_conn_receive_window"`
	MaxConnReceiveWindow    uint64 `json:"max_conn_receive_window"`
	MaxIdleTimeoutSec       int    `json:"max_idle_timeout"`
	KeepAlivePeriodSec      int    `json:"keep_alive_period"`
	DisablePathMTUDiscovery bool   `json:"disable_pmtud"`

	CongestionType string `json:"congestion_type"`
	BBRProfile     string `json:"bbr_profile"`

	MaxTxMbps int `json:"max_tx_mbps"`
	MaxRxMbps int `json:"max_rx_mbps"`

	HopIntervalSec    int `json:"hop_interval"`
	MinHopIntervalSec int `json:"min_hop_interval"`
	MaxHopIntervalSec int `json:"max_hop_interval"`

	FastOpen bool `json:"fast_open"`
	Lazy     bool `json:"lazy"`

	SocksAddr       string `json:"socks_addr"`
	SocksUsername   string `json:"socks_username"`
	SocksPassword   string `json:"socks_password"`
	SocksDisableUDP bool   `json:"socks_disable_udp"`

	HttpAddr     string `json:"http_addr"`
	HttpUsername string `json:"http_username"`
	HttpPassword string `json:"http_password"`
}

func buildCoreConfig(cfg *clientConfig) (*client.Config, error) {
	serverAddr, err := resolveServerAddr(cfg.Server)
	if err != nil {
		return nil, fmt.Errorf("resolve server: %w", err)
	}

	logMsg(LogLevelInfo, "Resolved server address: %s", serverAddr.String())

	coreConfig := &client.Config{
		ServerAddr: serverAddr,
		Auth:       cfg.Auth,
		FastOpen:   cfg.FastOpen,
	}

	if cfg.TLSSni != "" {
		coreConfig.TLSConfig.ServerName = cfg.TLSSni
	}
	coreConfig.TLSConfig.InsecureSkipVerify = cfg.TLSInsecure

	if cfg.TLSPinSHA256 != "" {
		pinHash := normalizeCertHash(cfg.TLSPinSHA256)
		coreConfig.TLSConfig.VerifyPeerCertificate = func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
			if len(rawCerts) == 0 {
				return errors.New("no certificates presented")
			}
			hash := sha256.Sum256(rawCerts[0])
			hashHex := hex.EncodeToString(hash[:])
			if hashHex == pinHash {
				return nil
			}
			return errors.New("certificate does not match pinned hash")
		}
	}

	if cfg.TLSCA != "" {
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM([]byte(cfg.TLSCA)) {
			return nil, fmt.Errorf("failed to parse CA PEM")
		}
		coreConfig.TLSConfig.RootCAs = pool
	}

	if cfg.TLSClientCert != "" && cfg.TLSClientKey != "" {
		cert, err := tls.X509KeyPair([]byte(cfg.TLSClientCert), []byte(cfg.TLSClientKey))
		if err != nil {
			return nil, fmt.Errorf("parse client certificate: %w", err)
		}
		coreConfig.TLSConfig.GetClientCertificate = func(*tls.CertificateRequestInfo) (*tls.Certificate, error) {
			return &cert, nil
		}
	}

	coreConfig.QUICConfig = client.QUICConfig{
		InitialStreamReceiveWindow:     cfg.InitStreamReceiveWindow,
		MaxStreamReceiveWindow:         cfg.MaxStreamReceiveWindow,
		InitialConnectionReceiveWindow: cfg.InitConnReceiveWindow,
		MaxConnectionReceiveWindow:     cfg.MaxConnReceiveWindow,
		DisablePathMTUDiscovery:        cfg.DisablePathMTUDiscovery,
	}
	if cfg.MaxIdleTimeoutSec > 0 {
		coreConfig.QUICConfig.MaxIdleTimeout = time.Duration(cfg.MaxIdleTimeoutSec) * time.Second
	}
	if cfg.KeepAlivePeriodSec > 0 {
		coreConfig.QUICConfig.KeepAlivePeriod = time.Duration(cfg.KeepAlivePeriodSec) * time.Second
	}

	if cfg.CongestionType != "" {
		coreConfig.CongestionConfig.Type = cfg.CongestionType
	}
	if cfg.BBRProfile != "" {
		coreConfig.CongestionConfig.BBRProfile = cfg.BBRProfile
	}

	if cfg.MaxTxMbps > 0 {
		coreConfig.BandwidthConfig.MaxTx = uint64(cfg.MaxTxMbps) * 125000
	}
	if cfg.MaxRxMbps > 0 {
		coreConfig.BandwidthConfig.MaxRx = uint64(cfg.MaxRxMbps) * 125000
	}

	if err := setupConnFactory(coreConfig, cfg, serverAddr); err != nil {
		return nil, err
	}

	return coreConfig, nil
}

func setupConnFactory(coreConfig *client.Config, cfg *clientConfig, serverAddr net.Addr) error {
	isHop := serverAddr.Network() == "udphop"
	hasObfs := strings.ToLower(cfg.ObfsType) == "salamander"

	if !isHop && !hasObfs {
		return nil
	}

	var ob obfs.Obfuscator
	if hasObfs {
		if cfg.ObfsPassword == "" {
			return fmt.Errorf("obfs password is required for salamander")
		}
		var err error
		ob, err = obfs.NewSalamanderObfuscator([]byte(cfg.ObfsPassword))
		if err != nil {
			return fmt.Errorf("create salamander obfuscator: %w", err)
		}
		logMsg(LogLevelInfo, "Obfuscation: salamander")
	}

	var newFunc func(addr net.Addr) (net.PacketConn, error)
	if isHop {
		hopAddr := serverAddr.(*udphop.UDPHopAddr)
		hopInterval := buildHopInterval(cfg)
		newFunc = func(addr net.Addr) (net.PacketConn, error) {
			return udphop.NewUDPHopPacketConn(hopAddr, hopInterval, func() (net.PacketConn, error) {
				return net.ListenPacket("udp", "")
			})
		}
		logMsg(LogLevelInfo, "Transport: UDP port hopping")
	} else {
		newFunc = func(addr net.Addr) (net.PacketConn, error) {
			return net.ListenPacket("udp", "")
		}
	}

	coreConfig.ConnFactory = &connFactory{
		newFunc:    newFunc,
		obfuscator: ob,
	}
	return nil
}

type connFactory struct {
	newFunc    func(addr net.Addr) (net.PacketConn, error)
	obfuscator obfs.Obfuscator
}

func (f *connFactory) New(addr net.Addr) (net.PacketConn, error) {
	conn, err := f.newFunc(addr)
	if err != nil {
		return nil, err
	}
	if f.obfuscator != nil {
		return obfs.WrapPacketConn(conn, f.obfuscator), nil
	}
	return conn, nil
}

func buildHopInterval(cfg *clientConfig) udphop.HopIntervalConfig {
	if cfg.MinHopIntervalSec > 0 && cfg.MaxHopIntervalSec > 0 {
		return udphop.HopIntervalConfig{
			Min: time.Duration(cfg.MinHopIntervalSec) * time.Second,
			Max: time.Duration(cfg.MaxHopIntervalSec) * time.Second,
		}
	}
	if cfg.HopIntervalSec > 0 {
		d := time.Duration(cfg.HopIntervalSec) * time.Second
		return udphop.HopIntervalConfig{Min: d, Max: d}
	}
	return udphop.HopIntervalConfig{
		Min: 30 * time.Second,
		Max: 30 * time.Second,
	}
}

func resolveServerAddr(server string) (net.Addr, error) {
	host, port, err := net.SplitHostPort(server)
	if err != nil {
		return nil, fmt.Errorf("invalid server address %q: %w", server, err)
	}

	if isPortHopping(port) {
		return udphop.ResolveUDPHopAddr(server)
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

func isPortHopping(port string) bool {
	return strings.Contains(port, "-") || strings.Contains(port, ",")
}

func normalizeCertHash(hash string) string {
	return strings.ToLower(strings.ReplaceAll(hash, ":", ""))
}
