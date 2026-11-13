package golib

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"time"

	"github.com/apernet/hysteria/extras/v2/transport/udphop"
	"github.com/apernet/hysteria/extras/v2/utils"
)

type directDNSConfig struct {
	Transport string              `json:"transport"`
	Servers   []string            `json:"servers"`
	Bootstrap map[string][]string `json:"bootstrap,omitempty"`
	Domains   []directDomainRule  `json:"domains"`
}

func parseDirectDNSConfig(raw string) (*directDNSConfig, error) {
	cfg := &directDNSConfig{Transport: dnsTransportUDP}
	if strings.TrimSpace(raw) == "" {
		return cfg, nil
	}
	if err := json.Unmarshal([]byte(raw), cfg); err != nil {
		return nil, err
	}
	cfg.Transport = strings.ToLower(strings.TrimSpace(cfg.Transport))
	if cfg.Transport == "" {
		cfg.Transport = dnsTransportUDP
	}
	switch cfg.Transport {
	case dnsTransportUDP, dnsTransportTCP, dnsTransportTLS, dnsTransportHTTPS:
	default:
		return nil, fmt.Errorf("unsupported Direct DNS transport %q", cfg.Transport)
	}
	bootstrap := make(map[string][]string, len(cfg.Bootstrap))
	for host, addresses := range cfg.Bootstrap {
		key := strings.ToLower(host)
		if _, exists := bootstrap[key]; exists {
			return nil, fmt.Errorf("duplicate Direct DNS bootstrap hostname %q", host)
		}
		bootstrap[key] = addresses
		if len(addresses) == 0 {
			return nil, fmt.Errorf("Direct DNS bootstrap for %q is empty", host)
		}
		for _, address := range addresses {
			if _, err := netip.ParseAddr(address); err != nil {
				return nil, fmt.Errorf("Direct DNS bootstrap %q must be a numeric IP", address)
			}
		}
	}
	cfg.Bootstrap = bootstrap
	for i, rawServer := range cfg.Servers {
		server, err := normalizeDNSServer(cfg.Transport, rawServer)
		if err != nil {
			return nil, err
		}
		cfg.Servers[i] = server
		var host string
		if cfg.Transport == dnsTransportHTTPS {
			host, _, err = dohDialAddr(server)
		} else {
			host, _, err = net.SplitHostPort(server)
		}
		if err != nil {
			return nil, err
		}
		if _, err := netip.ParseAddr(host); err != nil && len(cfg.Bootstrap[strings.ToLower(host)]) == 0 {
			return nil, fmt.Errorf("Direct DNS server %q needs explicit numeric bootstrap addresses", host)
		}
	}
	return cfg, nil
}

type directUDPResolver struct {
	session *Session
	server  string
}

func (r *directUDPResolver) id() string { return "direct-udp|" + r.server }
func (r *directUDPResolver) close()     {}

func (r *directUDPResolver) dialer() *net.Dialer {
	return &net.Dialer{Timeout: dnsDialTimeout, Control: func(network, address string, raw syscall.RawConn) error {
		if r.session.protector == nil {
			return errors.New("Direct DNS requires socket protection and network binding")
		}
		var ok bool
		if err := raw.Control(func(fd uintptr) {
			ok = r.session.protector.BindAndProtect(int32(fd))
		}); err != nil {
			return err
		}
		if !ok {
			return errors.New("failed to bind Direct DNS socket to the selected network")
		}
		return nil
	}}
}

func (r *directUDPResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	conn, err := r.dialer().DialContext(ctx, "udp", r.server)
	if err != nil {
		return nil, fmt.Errorf("dial Direct DNS %s: %w", r.server, err)
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	deadline := time.Now().Add(dnsIOTimeout)
	if contextDeadline, ok := ctx.Deadline(); ok && contextDeadline.Before(deadline) {
		deadline = contextDeadline
	}
	_ = conn.SetDeadline(deadline)
	if _, err := conn.Write(query); err != nil {
		return nil, fmt.Errorf("write Direct DNS query: %w", err)
	}
	response := make([]byte, 65535)
	n, err := conn.Read(response)
	if err != nil {
		return nil, fmt.Errorf("read Direct DNS response: %w", err)
	}
	response = response[:n]
	if err := checkDNSTxID(query, response); err != nil {
		return nil, err
	}
	return response, nil
}

func newDirectDNSUpstream(s *Session, cfg *directDNSConfig) (*dnsUpstream, error) {
	transport := cfg.Transport
	if transport == "" {
		transport = dnsTransportUDP
	}
	up := &dnsUpstream{transport: transport, ident: "direct-system"}
	for _, server := range cfg.Servers {
		up.servers = append(up.servers, server)
		direct := &directUDPResolver{session: s, server: server}
		switch transport {
		case dnsTransportUDP:
			up.resolvers = append(up.resolvers, direct)
		case dnsTransportTCP:
			up.resolvers = append(up.resolvers, newTCPResolverWithDialer(server, s.directDNSDialer(cfg)))
		case dnsTransportTLS:
			up.resolvers = append(up.resolvers, newTLSResolverWithDialer(server, nil, s.directDNSDialer(cfg)))
		case dnsTransportHTTPS:
			resolver, err := newHTTPSResolverWithDialer(server, nil, s.directDNSDialer(cfg))
			if err != nil {
				up.close()
				return nil, err
			}
			up.resolvers = append(up.resolvers, resolver)
		default:
			up.close()
			return nil, fmt.Errorf("unsupported Direct DNS transport %q", transport)
		}
	}
	if len(up.resolvers) > 0 {
		bootstrap, _ := json.Marshal(cfg.Bootstrap)
		up.ident = "direct-" + transport + "|" + strings.Join(up.servers, ",") + "|" + string(bootstrap)
	}
	return up, nil
}

// The endpoint hostname remains the TLS/HTTP identity. Only socket dialing uses
// bootstrap IPs, so connecting never depends on system DNS or the tunnel.
func (s *Session) directDNSDialer(cfg *directDNSConfig) dnsStreamDialer {
	return func(ctx context.Context, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		addresses := cfg.Bootstrap[strings.ToLower(host)]
		if _, err := netip.ParseAddr(host); err == nil {
			addresses = []string{host}
		}
		if len(addresses) == 0 {
			return nil, fmt.Errorf("Direct DNS server %q has no bootstrap IP", host)
		}
		dialer := (&directUDPResolver{session: s}).dialer()
		return raceDirectDNSAddresses(ctx, addresses, port, func(ctx context.Context, address string) (net.Conn, error) {
			return dialer.DialContext(ctx, "tcp", address)
		})
	}
}

// Race numeric dial targets within the caller's budget. A blackholed address
// must not consume the entire DNS attempt before another address can connect.
func raceDirectDNSAddresses(ctx context.Context, addresses []string, port string, dial dnsStreamDialer) (net.Conn, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		conn net.Conn
		err  error
	}
	results := make(chan result)
	for _, address := range addresses {
		go func(address string) {
			conn, err := dial(ctx, net.JoinHostPort(address, port))
			select {
			case results <- result{conn, err}:
			case <-ctx.Done():
				if conn != nil {
					_ = conn.Close()
				}
			}
		}(address)
	}
	var last error
	for range addresses {
		select {
		case r := <-results:
			if r.err == nil {
				return r.conn, nil
			}
			last = r.err
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, last
}

func (s *Session) directResolver() *net.Resolver {
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		cfg := s.directDNSConfig()
		if cfg == nil || len(cfg.Servers) == 0 {
			return nil, errors.New("Direct DNS has no configured resolver")
		}
		// net.Resolver retries truncated UDP answers using the TCP network.
		// Preserve that retry while keeping encrypted transports unchanged.
		if cfg.Transport == dnsTransportUDP && network == "tcp" {
			copy := *cfg
			copy.Transport = dnsTransportTCP
			cfg = &copy
		}
		upstream, err := newDirectDNSUpstream(s, cfg)
		if err != nil {
			return nil, err
		}
		// Keep Go's DNS parsing, CNAME handling and A/AAAA lookup behavior while
		// sending its wire query through the same transport as forwarded DNS.
		// net.Pipe is a stream, so net.Resolver uses DNS-over-TCP framing here;
		// that framing is local and independent of the selected wire transport.
		resolver, exchange := net.Pipe()
		go func() {
			defer exchange.Close()
			defer upstream.close()
			stop := context.AfterFunc(ctx, func() { _ = exchange.Close() })
			defer stop()
			query, err := readDNSFrame(exchange)
			if err != nil {
				return
			}
			response, err := upstream.exchange(ctx, query)
			if err == nil {
				_ = writeDNSFrame(exchange, response)
			}
		}()
		return resolver, nil
	}}
}

func (s *Session) lookupDirectHost(ctx context.Context, host string) ([]net.IPAddr, error) {
	if ip := net.ParseIP(host); ip != nil {
		return []net.IPAddr{{IP: ip}}, nil
	}
	if cfg := s.directDNSConfig(); cfg == nil || len(cfg.Servers) == 0 {
		return nil, errors.New("Direct DNS has no numeric system resolver")
	}
	return s.directResolver().LookupIPAddr(ctx, host)
}

func (s *Session) directDNSConfig() *directDNSConfig {
	s.directDNSMu.RLock()
	cfg := s.directDNS
	s.directDNSMu.RUnlock()
	return cfg
}

func (s *Session) resolveHost(server string) (net.Addr, error) {
	host, port, err := net.SplitHostPort(server)
	if err != nil {
		return nil, fmt.Errorf("invalid host address %q: %w", server, err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), serverResolveTimeout)
	defer cancel()
	ips, err := s.lookupDirectHost(ctx, host)
	if err != nil {
		return nil, fmt.Errorf("Direct DNS lookup failed for %q: %w", host, err)
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("Direct DNS returned no addresses for %q", host)
	}
	var selected net.IP
	for _, ip := range ips {
		if ip.IP.To4() != nil {
			selected = ip.IP
			break
		}
	}
	if selected == nil {
		selected = ips[0].IP
	}
	if isPortHopping(port) {
		ports := utils.ParsePortUnion(port).Ports()
		if len(ports) == 0 {
			return nil, fmt.Errorf("invalid port union %q", port)
		}
		return &udphop.UDPHopAddr{IP: selected, Ports: ports, PortStr: port}, nil
	}
	portNum, err := net.LookupPort("udp", port)
	if err != nil {
		return nil, err
	}
	return &net.UDPAddr{IP: selected, Port: portNum}, nil
}
