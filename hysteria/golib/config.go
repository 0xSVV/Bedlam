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
}

type connFactory struct {
	session       *Session
	newFunc       func(addr net.Addr) (net.PacketConn, error)
	salamanderPSK []byte
}

type trackedPacketConn struct {
	net.PacketConn
	session *Session
}

func (t *trackedPacketConn) Close() error {
	t.session.unregisterActiveConn(t.PacketConn)
	return t.PacketConn.Close()
}

func buildCoreConfig(cfg *clientConfig, serverAddr net.Addr, session *Session) (*client.Config, error) {
	coreConfig := &client.Config{
		ServerAddr: serverAddr,
		Auth:       cfg.Auth,
		FastOpen:   cfg.FastOpen,
	}

	if cfg.TLSSni != "" {
		coreConfig.TLSConfig.ServerName = cfg.TLSSni
	}
	coreConfig.TLSConfig.InsecureSkipVerify = cfg.TLSInsecure

	pinned := cfg.TLSPinSHA256 != ""
	if pinned {
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

	customCA := cfg.TLSCA != ""
	if customCA {
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM([]byte(cfg.TLSCA)) {
			return nil, fmt.Errorf("failed to parse CA PEM")
		}
		coreConfig.TLSConfig.RootCAs = pool
	}

	if (cfg.TLSClientCert != "") != (cfg.TLSClientKey != "") {
		return nil, fmt.Errorf("client cert and key must be set together")
	}
	mTLS := cfg.TLSClientCert != "" && cfg.TLSClientKey != ""
	if mTLS {
		cert, err := tls.X509KeyPair([]byte(cfg.TLSClientCert), []byte(cfg.TLSClientKey))
		if err != nil {
			return nil, fmt.Errorf("parse client certificate: %w", err)
		}
		coreConfig.TLSConfig.GetClientCertificate = func(*tls.CertificateRequestInfo) (*tls.Certificate, error) {
			return &cert, nil
		}
	}

	log(LogLevelInfo, srcTLS, "sni=%q insecure=%v pinned=%v custom-ca=%v mtls=%v",
		coreConfig.TLSConfig.ServerName, cfg.TLSInsecure, pinned, customCA, mTLS)
	log(LogLevelInfo, srcConfig, "auth=%s fast-open=%v",
		authSummary(cfg.Auth), cfg.FastOpen)

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
	if anyQUICTuned(cfg) {
		log(LogLevelInfo, srcTransport,
			"QUIC: stream-recv=[%d,%d] conn-recv=[%d,%d] idle=%ds keepalive=%ds pmtud-disabled=%v",
			cfg.InitStreamReceiveWindow, cfg.MaxStreamReceiveWindow,
			cfg.InitConnReceiveWindow, cfg.MaxConnReceiveWindow,
			cfg.MaxIdleTimeoutSec, cfg.KeepAlivePeriodSec,
			cfg.DisablePathMTUDiscovery)
	}

	if cfg.CongestionType != "" {
		coreConfig.CongestionConfig.Type = cfg.CongestionType
		coreConfig.CongestionConfig.BBRProfile = cfg.BBRProfile
		log(LogLevelInfo, srcTransport, "Congestion: type=%s bbr-profile=%q",
			cfg.CongestionType, cfg.BBRProfile)
	}

	if cfg.MaxTxMbps > 0 {
		coreConfig.BandwidthConfig.MaxTx = uint64(cfg.MaxTxMbps) * 125000
	}
	if cfg.MaxRxMbps > 0 {
		coreConfig.BandwidthConfig.MaxRx = uint64(cfg.MaxRxMbps) * 125000
	}
	if cfg.MaxTxMbps > 0 || cfg.MaxRxMbps > 0 {
		log(LogLevelInfo, srcTransport, "Bandwidth caps: tx=%dMbps rx=%dMbps",
			cfg.MaxTxMbps, cfg.MaxRxMbps)
	}

	if err := setupConnFactory(coreConfig, cfg, serverAddr, session); err != nil {
		return nil, err
	}

	return coreConfig, nil
}

func anyQUICTuned(cfg *clientConfig) bool {
	return cfg.InitStreamReceiveWindow != 0 || cfg.MaxStreamReceiveWindow != 0 ||
		cfg.InitConnReceiveWindow != 0 || cfg.MaxConnReceiveWindow != 0 ||
		cfg.MaxIdleTimeoutSec != 0 || cfg.KeepAlivePeriodSec != 0 ||
		cfg.DisablePathMTUDiscovery
}

func authSummary(auth string) string {
	if auth == "" {
		return "absent"
	}
	return fmt.Sprintf("present (%d chars)", len(auth))
}

func x509AppendOK(pem string) bool {
	pool := x509.NewCertPool()
	return pool.AppendCertsFromPEM([]byte(pem))
}

func validateClientKeyPair(cert, key string) error {
	_, err := tls.X509KeyPair([]byte(cert), []byte(key))
	if err != nil {
		return fmt.Errorf("invalid client key pair: %w", err)
	}
	return nil
}

func resolveHost(server string) (net.Addr, error) {
	host, port, err := net.SplitHostPort(server)
	if err != nil {
		return nil, fmt.Errorf("invalid host address %q: %w", server, err)
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

	pick := ips[0]
	for _, ip := range ips {
		if parsed := net.ParseIP(ip); parsed != nil && parsed.To4() != nil {
			pick = ip
			break
		}
	}
	log(LogLevelInfo, srcDNS, "Resolved %s → %s (candidates: %v)", host, pick, ips)
	return &net.UDPAddr{
		IP:   net.ParseIP(pick),
		Port: portNum,
	}, nil
}

func setupConnFactory(coreConfig *client.Config, cfg *clientConfig, serverAddr net.Addr, session *Session) error {
	isHop := serverAddr.Network() == "udphop"
	hasObfs := strings.ToLower(cfg.ObfsType) == "salamander"
	hasProtect := session.protector != nil

	if !isHop && !hasObfs && !hasProtect {
		return nil
	}

	var salamanderPSK []byte
	if hasObfs {
		if cfg.ObfsPassword == "" {
			return fmt.Errorf("obfs password is required for salamander")
		}
		salamanderPSK = []byte(cfg.ObfsPassword)
		log(LogLevelInfo, srcTransport, "Obfuscation: salamander")
	}

	listenUDP := func() (net.PacketConn, error) {
		conn, err := net.ListenPacket("udp", "")
		if err != nil {
			return nil, err
		}
		session.protectPacketConn(conn)
		return conn, nil
	}

	var newFunc func(addr net.Addr) (net.PacketConn, error)
	if isHop {
		if cfg.MinHopIntervalSec > 0 && cfg.MaxHopIntervalSec > 0 &&
			cfg.MinHopIntervalSec > cfg.MaxHopIntervalSec {
			return fmt.Errorf("min_hop_interval (%ds) exceeds max_hop_interval (%ds)",
				cfg.MinHopIntervalSec, cfg.MaxHopIntervalSec)
		}
		hopAddr := serverAddr.(*udphop.UDPHopAddr)
		hopInterval := buildHopInterval(cfg)
		newFunc = func(addr net.Addr) (net.PacketConn, error) {
			return udphop.NewUDPHopPacketConn(hopAddr, hopInterval, listenUDP)
		}
		log(LogLevelInfo, srcTransport,
			"UDP port hopping: addr=%s interval=[%s,%s]",
			hopAddr.String(), hopInterval.Min, hopInterval.Max)
	} else {
		newFunc = func(addr net.Addr) (net.PacketConn, error) {
			return listenUDP()
		}
	}

	coreConfig.ConnFactory = &connFactory{
		session:       session,
		newFunc:       newFunc,
		salamanderPSK: salamanderPSK,
	}
	return nil
}

func (f *connFactory) New(addr net.Addr) (net.PacketConn, error) {
	conn, err := f.newFunc(addr)
	if err != nil {
		return nil, err
	}
	tracked := &trackedPacketConn{PacketConn: conn, session: f.session}
	f.session.registerActiveConn(conn)
	if f.salamanderPSK != nil {
		return obfs.WrapPacketConnSalamander(tracked, f.salamanderPSK)
	}
	return tracked, nil
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

func isPortHopping(port string) bool {
	return strings.Contains(port, "-") || strings.Contains(port, ",")
}

func normalizeCertHash(hash string) string {
	return strings.ToLower(strings.ReplaceAll(hash, ":", ""))
}
