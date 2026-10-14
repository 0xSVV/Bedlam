package golib

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	singtun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/canceler"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/sys/unix"
)

const (
	udpSessionTimeout      = 300 * time.Second
	udpIdleRefreshInterval = time.Second
	udpSessionLimit        = 16384
)

func (s *Session) StartTUN(fd int32, mtu int32, inet4Prefix, inet6Prefix string, ipv6Mode string, dnsJSON string, directDNSJSON string) error {
	s.tunMu.Lock()
	defer s.tunMu.Unlock()

	if s.tunIface != nil {
		return fmt.Errorf("TUN already running")
	}

	c := s.currentClient()
	if c == nil {
		return fmt.Errorf("client not connected")
	}

	inet4, err := netip.ParsePrefix(inet4Prefix)
	if err != nil {
		return fmt.Errorf("parse inet4 prefix %q: %w", inet4Prefix, err)
	}
	inet6, err := netip.ParsePrefix(inet6Prefix)
	if err != nil {
		return fmt.Errorf("parse inet6 prefix %q: %w", inet6Prefix, err)
	}

	dnsCfg, err := parseDNSUpstream(dnsJSON)
	if err != nil {
		return fmt.Errorf("dns upstream: %w", err)
	}
	upstream, err := newDNSUpstream(c, dnsCfg)
	if err != nil {
		return fmt.Errorf("dns upstream: %w", err)
	}
	directCfg, err := parseDirectDNSConfig(directDNSJSON)
	if err != nil {
		upstream.close()
		return fmt.Errorf("direct DNS config: %w", err)
	}
	s.directDNSMu.Lock()
	s.directDNS = directCfg
	s.directDNSMu.Unlock()
	directUpstream, err := newDirectDNSUpstream(s, directCfg)
	if err != nil {
		upstream.close()
		return fmt.Errorf("direct DNS upstream: %w", err)
	}
	if ipv6Mode != "enabled" && ipv6Mode != "disabled" && ipv6Mode != "bypass_only" {
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("unsupported IPv6 mode %q", ipv6Mode)
	}

	// The Kotlin caller owns fd for the duration of this synchronous call. Give
	// sing-tun a duplicate so its Close cannot invalidate a descriptor reused by
	// Android while the caller unwinds an error.
	ownedFD, err := unix.Dup(int(fd))
	if err != nil {
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("duplicate TUN fd: %w", err)
	}
	tunOpts := tunOptions(int32(ownedFD), mtu, inet4, inet6)
	tunIface, err := singtun.New(tunOpts)
	if err != nil {
		_ = unix.Close(ownedFD)
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("create TUN: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	policy, err := newDNSPolicy(directCfg.Domains, ipv6Mode)
	if err != nil {
		cancel()
		tunIface.Close()
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("Direct DNS policy: %w", err)
	}
	s.domainIPs.reset()
	handler := &tunHandler{session: s, client: c, ipv6Enabled: ipv6Mode != "disabled", ipv6Mode: ipv6Mode, dns: upstream, directDNS: directUpstream, dnsPolicy: policy}
	stack, err := singtun.NewGVisor(tunStackOptions(ctx, tunIface, tunOpts, handler))
	if err != nil {
		cancel()
		tunIface.Close()
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("create TUN stack: %w", err)
	}

	if err := tunIface.Start(); err != nil {
		cancel()
		tunIface.Close()
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("start TUN: %w", err)
	}

	if err := stack.Start(); err != nil {
		cancel()
		_ = stack.Close()
		tunIface.Close()
		upstream.close()
		directUpstream.close()
		return fmt.Errorf("start TUN stack: %w", err)
	}

	s.tunIface = tunIface
	s.tunStack = stack
	s.tunCancel = cancel
	s.tunDNS = upstream

	log(LogLevelInfo, srcTun, "TUN started (fd=%d, mtu=%d, stack=gvisor, ipv6=%v, dns=%s)",
		fd, mtu, ipv6Mode, upstream.id())
	return nil
}

func tunOptions(fd int32, mtu int32, inet4, inet6 netip.Prefix) singtun.Options {
	return singtun.Options{
		FileDescriptor: int(fd),
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{inet4},
		Inet6Address:   []netip.Prefix{inet6},
		DNSMode:        singtun.DNSModeDisabled,
	}
}

func tunStackOptions(ctx context.Context, tunIface singtun.Tun, tunOpts singtun.Options, handler singtun.Handler) singtun.StackOptions {
	return singtun.StackOptions{
		Context:    ctx,
		Tun:        tunIface,
		TunOptions: tunOpts,
		UDPTimeout: udpSessionTimeout,
		UDPMapping: singtun.NATMappingAddressAndPortDependent,
		UDPNATMax:  udpSessionLimit,
		Handler:    handler,
		Logger:     &tunLogger{},
	}
}

func (h *tunHandler) rejectIPv6(dest M.Socksaddr) bool {
	return !h.ipv6Enabled && dest.Addr.Is6() && !dest.Addr.Is4In6()
}

func (h *tunHandler) isResolverAddr(dest M.Socksaddr) bool {
	return h.dns != nil && h.dns.isListenAddr(dest.Addr)
}

func (h *tunHandler) answerLocally(query []byte) []byte {
	if h.ipv6Mode != "disabled" && !(h.ipv6Mode == "" && !h.ipv6Enabled) {
		return nil
	}
	qtype, ok := dnsQuestionType(query)
	if !ok || qtype != dnsTypeAAAA {
		return nil
	}
	resp := buildNoData(query)
	if resp != nil {
		log(LogLevelDebug, srcDNS, "AAAA query answered empty: IPv6 disabled")
	}
	return resp
}

func (h *tunHandler) countDNS(tx, rx int) {
	h.session.addTx(tx)
	h.session.addRx(rx)
}

func (s *Session) StopTUN() error {
	s.tunMu.Lock()
	iface := s.tunIface
	stack := s.tunStack
	cancel := s.tunCancel
	dns := s.tunDNS
	s.tunIface = nil
	s.tunStack = nil
	s.tunCancel = nil
	s.tunDNS = nil
	s.tunMu.Unlock()

	if iface == nil {
		return nil
	}

	if cancel != nil {
		cancel()
	}

	done := make(chan error, 1)
	go func() {
		if stack != nil {
			_ = stack.Close()
		}
		done <- iface.Close()
	}()
	var err error
	select {
	case err = <-done:
	case <-time.After(3 * time.Second):
		err = fmt.Errorf("TUN close timed out")
		log(LogLevelWarn, srcTun, "TUN close timed out; continuing")
	}
	if dns != nil {
		dns.close()
	}

	log(LogLevelInfo, srcTun, "TUN stopped")
	return err
}

func (h *tunHandler) JudgeFlow(uint8, netip.AddrPort, netip.AddrPort, []byte) singtun.FlowVerdict {
	return singtun.FlowVerdict{Action: singtun.ActionAccept}
}

func (h *tunHandler) NewDNSPacket([]byte, M.Socksaddr, M.Socksaddr, N.PacketWriter) {}

func (h *tunHandler) NewConnectionEx(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	err := h.newConnection(ctx, conn, source, destination)
	if onClose != nil {
		onClose(err)
	}
}

func (h *tunHandler) newConnection(ctx context.Context, conn net.Conn, source M.Socksaddr, destination M.Socksaddr) error {
	defer conn.Close()

	if err := N.ReportHandshakeSuccess(conn); err != nil {
		return err
	}

	if h.rejectIPv6(destination) {
		log(LogLevelDebug, srcTun, "TCP refused, IPv6 disabled: %s → %s", source, destination)
		return fmt.Errorf("IPv6 disabled: %s", destination)
	}

	if destination.Port == 53 && h.dns != nil {
		return h.serveDNSStream(ctx, conn)
	}
	if h.isResolverAddr(destination) {
		return fmt.Errorf("local resolver refuses %s", destination)
	}

	target := destination.String()
	log(LogLevelDebug, srcTun, "TCP: %s → %s", source, target)

	var remote net.Conn
	var err error
	directFlow := h.session.domainIPs.hasAnyOwner(destination.Addr, time.Now())
	if directFlow {
		dialer := (&directUDPResolver{session: h.session}).dialer()
		remote, err = dialer.DialContext(ctx, "tcp", target)
		if err != nil {
			return fmt.Errorf("protected Direct TCP dial failed: %w", err)
		}
	} else {
		remote, err = h.client.TCP(target)
	}
	if err != nil {
		if tcpDialErrLimiter.allow(target) {
			log(LogLevelWarn, srcTun, "TCP dial error: %s → %s: %s", source, target, err)
		}
		return err
	}
	defer remote.Close()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		if directFlow {
			_, _ = io.Copy(remote, conn)
		} else {
			_, _ = io.Copy(&countingWriter{w: remote, add: h.session.addTx}, conn)
		}
		_ = remote.Close()
	}()
	go func() {
		defer wg.Done()
		if directFlow {
			_, _ = io.Copy(conn, remote)
		} else {
			_, _ = io.Copy(&countingWriter{w: conn, add: h.session.addRx}, remote)
		}
		_ = conn.Close()
	}()
	wg.Wait()
	return nil
}

type countingWriter struct {
	w   io.Writer
	add func(int)
}

func (c *countingWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.add(n)
	return n, err
}

func (h *tunHandler) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	err := h.newPacketConnection(ctx, conn, source, destination)
	if onClose != nil {
		onClose(err)
	}
}

func (h *tunHandler) newPacketConnection(ctx context.Context, conn N.PacketConn, source M.Socksaddr, destination M.Socksaddr) error {
	defer conn.Close()

	if h.rejectIPv6(destination) {
		log(LogLevelDebug, srcTun, "UDP refused, IPv6 disabled: %s → %s", source, destination)
		return fmt.Errorf("IPv6 disabled: %s", destination)
	}

	dest := destination.String()
	log(LogLevelDebug, srcTun, "UDP session: %s → %s", source, dest)

	// Every DNS query answers from the configured upstream, not just the ones
	// addressed to the on-TUN resolver: an app with a hard-coded resolver must
	// not silently get plain DNS when the user picked an encrypted transport.
	if destination.Port == 53 && h.dns != nil {
		return h.serveDNSPackets(ctx, conn, dest)
	}

	if h.session.domainIPs.hasAnyOwner(destination.Addr, time.Now()) {
		return h.handleDirectUDPRelay(ctx, conn, destination)
	}
	return h.handleUDPRelay(ctx, conn, destination)
}

const dnsStreamIdleTimeout = 10 * time.Second

func srcAddrForDNS(address string) M.Socksaddr {
	if ap, err := netip.ParseAddrPort(address); err == nil {
		return M.SocksaddrFromNetIP(ap)
	}
	return M.Socksaddr{}
}

func (h *tunHandler) selectDNSUpstream(query []byte) (*dnsUpstream, string) {
	if h.dnsPolicy != nil {
		if ruleID, direct := h.dnsPolicy.directRuleFor(query); direct {
			if h.directDNS != nil && len(h.directDNS.resolvers) > 0 {
				return h.directDNS, ruleID
			}
			return nil, ruleID
		}
	}
	return h.dns, ""
}

func (h *tunHandler) cachedDNSResponse(upstream *dnsUpstream, query []byte, ruleID string) []byte {
	if upstream == nil {
		return nil
	}
	resp := h.session.dnsCache.tryCached(upstream, query)
	if resp == nil || ruleID == "" {
		return resp
	}
	addresses, _, err := domainAnswerAddresses(query, resp)
	if err != nil || !h.session.domainIPs.hasFresh(ruleID, addresses, time.Now()) {
		h.session.dnsCache.drop(upstream, query)
		return nil
	}
	return resp
}

func (h *tunHandler) resolveDNS(ctx context.Context, upstream *dnsUpstream, query []byte, ruleID string) ([]byte, error) {
	if upstream == nil {
		return nil, fmt.Errorf("Direct DNS is unavailable")
	}
	countTraffic := h.countDNS
	if ruleID != "" {
		countTraffic = func(int, int) {}
	}
	resp, err := h.session.dnsCache.resolve(ctx, upstream, query, countTraffic)
	if err != nil || ruleID == "" {
		return resp, err
	}
	addresses, ttl, err := domainAnswerAddresses(query, resp)
	if err != nil {
		return nil, err
	}
	if !h.session.domainIPs.admit(ruleID, addresses, ttl, time.Now()) {
		return nil, fmt.Errorf("Direct DNS answer could not be admitted to the route lease registry")
	}
	return resp, nil
}

func (h *tunHandler) serveDNSPackets(ctx context.Context, conn N.PacketConn, defaultDest string) error {
	for {
		buffer := buf.NewPacket()
		dest, err := conn.ReadPacket(buffer)
		if err != nil {
			buffer.Release()
			return err
		}

		query := make([]byte, buffer.Len())
		copy(query, buffer.Bytes())
		buffer.Release()

		dnsAddr := dest.String()
		if !isDNSPort(dnsAddr) {
			dnsAddr = defaultDest
		}
		resolver, ruleID := h.selectDNSUpstream(query)
		if resolver == nil {
			if sf := buildServFail(query); sf != nil {
				_ = conn.WritePacket(buf.As(sf), srcAddrForDNS(dnsAddr))
			}
			continue
		}

		log(LogLevelDebug, srcDNS, "DNS query for %s via %s (%d bytes)", dnsAddr, resolver.id(), len(query))

		src := srcAddrForDNS(dnsAddr)

		if resp := h.answerLocally(query); resp != nil {
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, srcDNS, "DNS write to local error: %s", werr)
			}
			continue
		}

		if resp := h.cachedDNSResponse(resolver, query, ruleID); resp != nil {
			log(LogLevelDebug, srcDNS, "DNS response: %d bytes from %s (cached)", len(resp), resolver.id())
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, srcDNS, "DNS write to local error: %s", werr)
			}
			continue
		}
		if _, _, ok := parseDNSQuery(query); !ok {
			logDNSError(resolver.id(), query, errDNSQueryInvalid)
			if sf := buildServFail(query); sf != nil {
				if werr := conn.WritePacket(buf.As(sf), src); werr != nil {
					log(LogLevelDebug, srcDNS, "DNS servfail write error: %s", werr)
				}
			}
			continue
		}

		qctx, cancel := context.WithTimeout(ctx, dnsQueryTimeout)
		accepted := h.session.dnsWorkScheduler().submit(dnsWork{ctx: qctx, run: func(qctx context.Context) {
			defer cancel()
			resp, err := h.resolveDNS(qctx, resolver, query, ruleID)

			if err != nil {
				logDNSError(resolver.id(), query, err)
				if sf := buildServFail(query); sf != nil {
					if werr := conn.WritePacket(buf.As(sf), src); werr != nil {
						log(LogLevelDebug, srcDNS, "DNS servfail write error: %s", werr)
					}
				}
				return
			}
			log(LogLevelDebug, srcDNS, "DNS response: %d bytes from %s", len(resp), resolver.id())
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, srcDNS, "DNS write to local error: %s", werr)
			}
		}})
		if !accepted {
			cancel()
			if sf := buildServFail(query); sf != nil {
				if werr := conn.WritePacket(buf.As(sf), src); werr != nil {
					log(LogLevelDebug, srcDNS, "DNS queue overflow response error: %s", werr)
				}
			}
		}
	}
}

func (h *tunHandler) serveDNSStream(ctx context.Context, conn net.Conn) error {
	for {
		_ = conn.SetReadDeadline(time.Now().Add(dnsStreamIdleTimeout))
		query, err := readDNSFrame(conn)
		if err != nil {
			return nil
		}
		_ = conn.SetReadDeadline(time.Time{})

		resolver, ruleID := h.selectDNSUpstream(query)
		resp := h.answerLocally(query)
		if resp == nil && resolver == nil {
			resp = buildServFail(query)
		}
		if resp == nil {
			if cached := h.cachedDNSResponse(resolver, query, ruleID); cached != nil {
				resp = cached
			} else if _, _, ok := parseDNSQuery(query); !ok {
				logDNSError(resolver.id(), query, errDNSQueryInvalid)
				resp = buildServFail(query)
			} else {
				qctx, cancel := context.WithTimeout(ctx, dnsQueryTimeout)
				type answer struct {
					response []byte
					err      error
				}
				result := make(chan answer, 1)
				accepted := h.session.dnsWorkScheduler().submit(dnsWork{ctx: qctx, run: func(qctx context.Context) {
					response, resolveErr := h.resolveDNS(qctx, resolver, query, ruleID)
					result <- answer{response: response, err: resolveErr}
				}})
				if !accepted {
					cancel()
					resp = buildServFail(query)
				} else {
					select {
					case resolved := <-result:
						cancel()
						resp, err = resolved.response, resolved.err
					case <-qctx.Done():
						cancel()
						err = qctx.Err()
					}
				}
				if err != nil {
					logDNSError(resolver.id(), query, err)
					resp = buildServFail(query)
				}
			}
			if resp == nil {
				return errDNSQueryInvalid
			}
		}
		_ = conn.SetWriteDeadline(time.Now().Add(dnsIOTimeout))
		if err := writeDNSFrame(conn, resp); err != nil {
			return err
		}
	}
}

func logDNSError(upstreamID string, query []byte, err error) {
	if errors.Is(err, errDNSQueryInvalid) {
		if dnsErrLimiter.allow("invalid|" + upstreamID) {
			log(LogLevelInfo, srcDNS, "Refused a packet on port 53 that is not a single DNS query (%d bytes)", len(query))
		}
		return
	}
	if dnsErrLimiter.allow(upstreamID) {
		log(LogLevelWarn, srcDNS, "DNS error: %s: %s", upstreamID, err)
	}
}

func (h *tunHandler) handleDirectUDPRelay(ctx context.Context, conn N.PacketConn, origin M.Socksaddr) error {
	dialer := (&directUDPResolver{session: h.session}).dialer()
	remote, err := dialer.DialContext(ctx, "udp", origin.String())
	if err != nil {
		return fmt.Errorf("protected Direct UDP dial failed: %w", err)
	}
	defer remote.Close()
	defer conn.Close()
	done := make(chan error, 2)
	go func() {
		packet := make([]byte, 65535)
		for {
			n, err := remote.Read(packet)
			if err != nil {
				done <- err
				return
			}
			if err := conn.WritePacket(buf.As(packet[:n]), origin); err != nil {
				done <- err
				return
			}
		}
	}()
	go func() {
		for {
			packet := buf.NewPacket()
			_, err := conn.ReadPacket(packet)
			if err != nil {
				packet.Release()
				done <- err
				return
			}
			_, err = remote.Write(packet.Bytes())
			packet.Release()
			if err != nil {
				done <- err
				return
			}
		}
	}()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case err := <-done:
		return err
	}
}

func (h *tunHandler) handleUDPRelay(ctx context.Context, conn N.PacketConn, origin M.Socksaddr) error {
	rc, err := h.client.UDP()
	if err != nil {
		log(LogLevelWarn, srcTun, "UDP session open failed: %s", err)
		return err
	}
	defer rc.Close()

	done := make(chan struct{}, 2)

	go func() {
		refresh := newUDPIdleRefresh(conn)
		for {
			data, _, err := rc.Receive()
			if err != nil {
				done <- struct{}{}
				return
			}
			h.session.addRx(len(data))
			if err := conn.WritePacket(buf.As(data), origin); err != nil {
				done <- struct{}{}
				return
			}
			refresh.touch(time.Now())
		}
	}()

	go func() {
		for {
			buffer := buf.NewPacket()
			dest, err := conn.ReadPacket(buffer)
			if err != nil {
				buffer.Release()
				done <- struct{}{}
				return
			}
			n := buffer.Len()
			err = rc.Send(buffer.Bytes(), dest.String())
			buffer.Release()
			if err != nil {
				done <- struct{}{}
				return
			}
			h.session.addTx(n)
		}
	}()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-done:
		return nil
	}
}

type udpIdleRefresh struct {
	conn canceler.PacketConn
	last time.Time
}

func newUDPIdleRefresh(conn N.PacketConn) *udpIdleRefresh {
	r := &udpIdleRefresh{}
	r.conn, _ = conn.(canceler.PacketConn)
	return r
}

func (r *udpIdleRefresh) touch(now time.Time) {
	if r.conn == nil || now.Sub(r.last) < udpIdleRefreshInterval {
		return
	}
	r.last = now
	r.conn.SetTimeout(udpSessionTimeout)
}

var (
	tcpDialErrLimiter = newRateLimiter(2 * time.Second)
	dnsErrLimiter     = newRateLimiter(2 * time.Second)
)
