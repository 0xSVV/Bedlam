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
)

const (
	udpSessionTimeout      = 300 * time.Second
	udpIdleRefreshInterval = time.Second
	udpSessionLimit        = 16384
)

func (s *Session) StartTUN(fd int32, mtu int32, inet4Prefix, inet6Prefix string, enableIPv6 bool, dnsJSON string) error {
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

	tunOpts := tunOptions(fd, mtu, inet4, inet6)
	tunIface, err := singtun.New(tunOpts)
	if err != nil {
		upstream.close()
		return fmt.Errorf("create TUN: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	handler := &tunHandler{session: s, client: c, ipv6Enabled: enableIPv6, dns: upstream}
	stack, err := singtun.NewGVisor(tunStackOptions(ctx, tunIface, tunOpts, handler))
	if err != nil {
		cancel()
		tunIface.Close()
		upstream.close()
		return fmt.Errorf("create TUN stack: %w", err)
	}

	if err := tunIface.Start(); err != nil {
		cancel()
		tunIface.Close()
		upstream.close()
		return fmt.Errorf("start TUN: %w", err)
	}

	if err := stack.Start(); err != nil {
		cancel()
		_ = stack.Close()
		tunIface.Close()
		upstream.close()
		return fmt.Errorf("start TUN stack: %w", err)
	}

	s.tunIface = tunIface
	s.tunStack = stack
	s.tunCancel = cancel
	s.tunDNS = upstream

	log(LogLevelInfo, srcTun, "TUN started (fd=%d, mtu=%d, stack=gvisor, ipv6=%v, dns=%s)",
		fd, mtu, enableIPv6, upstream.id())
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
	if h.ipv6Enabled {
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

	remote, err := h.client.TCP(target)
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
		_, _ = io.Copy(&countingWriter{w: remote, add: h.session.addTx}, conn)
		_ = remote.Close()
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(&countingWriter{w: conn, add: h.session.addRx}, remote)
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

	return h.handleUDPRelay(ctx, conn, destination)
}

const (
	maxConcurrentDNS     = 64
	dnsStreamIdleTimeout = 10 * time.Second
)

func (h *tunHandler) serveDNSPackets(ctx context.Context, conn N.PacketConn, defaultDest string) error {
	sem := make(chan struct{}, maxConcurrentDNS)
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
		resolver := h.dns

		log(LogLevelDebug, srcDNS, "DNS query for %s via %s (%d bytes)", dnsAddr, resolver.id(), len(query))

		var src M.Socksaddr
		if ap, perr := netip.ParseAddrPort(dnsAddr); perr == nil {
			src = M.SocksaddrFromNetIP(ap)
		}

		if resp := h.answerLocally(query); resp != nil {
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, srcDNS, "DNS write to local error: %s", werr)
			}
			continue
		}

		if resp := h.session.dnsCache.tryCached(resolver, query); resp != nil {
			log(LogLevelDebug, srcDNS, "DNS response: %d bytes from %s (cached)", len(resp), resolver.id())
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, srcDNS, "DNS write to local error: %s", werr)
			}
			continue
		}

		go func() {
			// Waiting for a slot here rather than in the read loop keeps the
			// loop draining, so a cached name never queues behind a burst of
			// misses.
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-sem }()

			qctx, cancel := context.WithTimeout(ctx, dnsQueryTimeout)
			defer cancel()
			resp, err := h.session.dnsCache.resolve(qctx, resolver, query, h.countDNS)

			if err != nil {
				logDNSError(resolver.id(), err)
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
		}()
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

		resp := h.answerLocally(query)
		if resp == nil {
			qctx, cancel := context.WithTimeout(ctx, dnsQueryTimeout)
			resp, err = h.session.dnsCache.resolve(qctx, h.dns, query, h.countDNS)
			cancel()
			if err != nil {
				logDNSError(h.dns.id(), err)
				resp = buildServFail(query)
				if resp == nil {
					return err
				}
			}
		}
		_ = conn.SetWriteDeadline(time.Now().Add(dnsIOTimeout))
		if err := writeDNSFrame(conn, resp); err != nil {
			return err
		}
	}
}

func logDNSError(upstreamID string, err error) {
	key := upstreamID
	if errors.Is(err, errDNSQueryInvalid) {
		key = "invalid|" + upstreamID
	}
	if dnsErrLimiter.allow(key) {
		log(LogLevelWarn, srcDNS, "DNS error: %s: %s", upstreamID, err)
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
