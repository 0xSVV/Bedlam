package golib

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/apernet/hysteria/extras/v2/transport/udphop"
	"github.com/apernet/hysteria/extras/v2/utils"
)

type directDNSConfig struct {
	Servers []string           `json:"servers"`
	Domains []directDomainRule `json:"domains"`
}

func parseDirectDNSConfig(raw string) (*directDNSConfig, error) {
	cfg := &directDNSConfig{}
	if strings.TrimSpace(raw) == "" {
		return cfg, nil
	}
	if err := json.Unmarshal([]byte(raw), cfg); err != nil {
		return nil, err
	}
	for i, server := range cfg.Servers {
		host, port, err := net.SplitHostPort(server)
		if err != nil {
			return nil, fmt.Errorf("server %q must be a numeric IP:port: %w", server, err)
		}
		if _, err := netip.ParseAddr(strings.Trim(host, "[]")); err != nil {
			return nil, fmt.Errorf("server %q must use a numeric IP: %w", server, err)
		}
		p, err := strconv.Atoi(port)
		if err != nil || p < 1 || p > 65535 {
			return nil, fmt.Errorf("server %q has invalid port", server)
		}
		cfg.Servers[i] = net.JoinHostPort(strings.Trim(host, "[]"), strconv.Itoa(p))
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
		var fdErr error
		if err := raw.Control(func(fd uintptr) {
			ok = r.session.protector.BindAndProtect(int32(fd))
		}); err != nil {
			return err
		}
		if fdErr != nil {
			return fdErr
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
	_ = conn.SetDeadline(time.Now().Add(dnsIOTimeout))
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
	up := &dnsUpstream{transport: dnsTransportUDP, ident: "direct-system"}
	for _, server := range cfg.Servers {
		up.servers = append(up.servers, server)
		up.resolvers = append(up.resolvers, &directUDPResolver{session: s, server: server})
	}
	if len(up.resolvers) > 0 {
		up.ident = "direct-udp|" + strings.Join(up.servers, ",")
	}
	return up, nil
}

func (s *Session) directResolver() *net.Resolver {
	cfg := s.directDNSConfig()
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		if len(cfg.Servers) == 0 {
			return nil, errors.New("Direct DNS has no numeric system resolver")
		}
		var last error
		for _, dnsServer := range cfg.Servers {
			dialer := (&directUDPResolver{session: s, server: dnsServer}).dialer()
			conn, err := dialer.DialContext(ctx, network, dnsServer)
			if err == nil {
				return conn, nil
			}
			last = err
		}
		return nil, last
	}}
}

func (s *Session) lookupDirectHost(ctx context.Context, host string) ([]net.IPAddr, error) {
	if ip := net.ParseIP(host); ip != nil {
		return []net.IPAddr{{IP: ip}}, nil
	}
	if len(s.directDNSConfig().Servers) == 0 {
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
