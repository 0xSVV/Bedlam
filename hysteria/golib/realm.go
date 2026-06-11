package golib

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/apernet/hysteria/extras/v2/realm"
)

// Ref: upstream app/cmd/client.go defaultRealmSTUNServers.
var defaultRealmSTUNServers = []string{
	"stun.nextcloud.com:3478",
	"stun.sip.us:3478",
	"global.stun.twilio.com:3478",
}

const (
	defaultRealmStunTimeout  = 10 * time.Second
	defaultRealmPunchTimeout = 10 * time.Second
)

func isRealmAddr(server string) bool {
	return strings.HasPrefix(server, realm.SchemeHTTPS+"://") ||
		strings.HasPrefix(server, realm.SchemeHTTP+"://")
}

// dialRealm runs STUN discovery and UDP hole punching against a rendezvous
// service, then returns a client.Config bound to the punched socket. It is
// called once per dial (including reconnects), so every reconnect performs a
// fresh punch.
func dialRealm(cfg *clientConfig, session *Session) (*client.Config, error) {
	addr, err := realm.ParseAddr(cfg.Server)
	if err != nil {
		return nil, fmt.Errorf("invalid realm address: %w", err)
	}

	coreConfig := &client.Config{Auth: cfg.Auth, FastOpen: cfg.FastOpen}
	if err := applyClientOptions(coreConfig, cfg, addr.Host); err != nil {
		return nil, err
	}

	wrap, err := buildObfsWrapper(cfg)
	if err != nil {
		return nil, err
	}

	baseConn, err := session.listenProtectedUDP(addr.LocalPort)
	if err != nil {
		return nil, fmt.Errorf("realm: open UDP socket: %w", err)
	}
	success := false
	defer func() {
		if !success {
			_ = baseConn.Close()
		}
	}()

	log(LogLevelInfo, srcTransport, "Realm: dialing rendezvous %s (realm=%s)",
		addr.HostPort, addr.RealmID)

	ctx := context.Background()
	stunServers := realmSTUNServers(cfg, addr)
	localAddrs, err := realm.Discover(ctx, baseConn, realm.STUNConfig{
		Servers: stunServers,
		Timeout: durationOrDefault(cfg.RealmStunTimeoutMs, defaultRealmStunTimeout),
	})
	if err != nil {
		return nil, fmt.Errorf("realm: STUN discovery: %w", err)
	}
	log(LogLevelInfo, srcTransport, "Realm: STUN found %d local candidate(s)", len(localAddrs))

	meta, err := realm.NewPunchMetadata()
	if err != nil {
		return nil, fmt.Errorf("realm: punch metadata: %w", err)
	}

	rClient, err := realm.NewClientFromAddr(addr, session.realmHTTPClient(cfg.RealmInsecure))
	if err != nil {
		return nil, fmt.Errorf("realm: client: %w", err)
	}
	connectResp, err := rClient.Connect(ctx, addr.RealmID, realm.ConnectRequest{
		Addresses:     addrPortStrings(localAddrs),
		PunchMetadata: meta,
	})
	if err != nil {
		return nil, fmt.Errorf("realm: connect: %w", err)
	}

	peerAddrs, err := parseAddrPorts(connectResp.Addresses)
	if err != nil {
		return nil, fmt.Errorf("realm: peer addresses: %w", err)
	}
	result, err := realm.Punch(ctx, baseConn, localAddrs, peerAddrs, connectResp.PunchMetadata, realm.PunchConfig{
		Timeout: durationOrDefault(cfg.RealmPunchTimeoutMs, defaultRealmPunchTimeout),
	})
	if err != nil {
		return nil, fmt.Errorf("realm: punch: %w", err)
	}
	log(LogLevelInfo, srcTransport, "Realm: punched through to %s", result.PeerAddr.String())

	coreConfig.ServerAddr = &net.UDPAddr{
		IP:   net.IP(result.PeerAddr.Addr().AsSlice()),
		Port: int(result.PeerAddr.Port()),
	}

	tracked := &trackedPacketConn{PacketConn: baseConn, session: session}
	session.registerActiveConn(baseConn)
	finalConn := net.PacketConn(tracked)
	if wrap != nil {
		finalConn, err = wrap(tracked)
		if err != nil {
			return nil, err
		}
	}
	coreConfig.ConnFactory = &singleUseConnFactory{conn: finalConn}

	success = true
	return coreConfig, nil
}

func realmSTUNServers(cfg *clientConfig, addr *realm.Addr) []string {
	if override := addr.Params["stun"]; len(override) > 0 {
		return append([]string(nil), override...)
	}
	if len(cfg.RealmStunServers) > 0 {
		return append([]string(nil), cfg.RealmStunServers...)
	}
	return append([]string(nil), defaultRealmSTUNServers...)
}

func durationOrDefault(ms int, def time.Duration) time.Duration {
	if ms > 0 {
		return time.Duration(ms) * time.Millisecond
	}
	return def
}

// listenProtectedUDP opens a UDP socket on the requested local port (0 =
// ephemeral) and protects it from the VPN before any traffic flows.
func (s *Session) listenProtectedUDP(localPort int) (net.PacketConn, error) {
	laddr := ""
	if localPort != 0 {
		laddr = fmt.Sprintf(":%d", localPort)
	}
	conn, err := net.ListenPacket("udp", laddr)
	if err != nil {
		return nil, err
	}
	if perr := s.protectPacketConn(conn); perr != nil {
		_ = conn.Close()
		return nil, perr
	}
	return conn, nil
}

// realmHTTPClient returns an HTTP client whose sockets are protected from the
// VPN, so the rendezvous request survives reconnects while the TUN is up.
func (s *Session) realmHTTPClient(insecure bool) *http.Client {
	dialer := &net.Dialer{Timeout: 15 * time.Second}
	if p := s.protector; p != nil {
		dialer.Control = func(_, _ string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				p.Protect(int32(fd))
			})
		}
	}
	transport := &http.Transport{DialContext: dialer.DialContext}
	if insecure {
		transport.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	return &http.Client{Transport: transport, Timeout: 30 * time.Second}
}

type singleUseConnFactory struct {
	mu   sync.Mutex
	conn net.PacketConn
}

func (f *singleUseConnFactory) New(net.Addr) (net.PacketConn, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.conn == nil {
		return nil, fmt.Errorf("realm connection factory already used")
	}
	conn := f.conn
	f.conn = nil
	return conn, nil
}

func addrPortStrings(addrs []netip.AddrPort) []string {
	out := make([]string, 0, len(addrs))
	for _, a := range addrs {
		out = append(out, a.String())
	}
	return out
}

func parseAddrPorts(addrs []string) ([]netip.AddrPort, error) {
	out := make([]netip.AddrPort, 0, len(addrs))
	for _, s := range addrs {
		a, err := netip.ParseAddrPort(s)
		if err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, nil
}
