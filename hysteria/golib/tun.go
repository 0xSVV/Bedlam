package golib

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"time"

	singtun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func (s *Session) StartTUN(fd int32, mtu int32, inet4Prefix, inet6Prefix string, enableIPv6 bool) error {
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

	tunOpts := singtun.Options{
		FileDescriptor: int(fd),
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{inet4},
		Inet6Address:   []netip.Prefix{inet6},
	}

	tunIface, err := singtun.New(tunOpts)
	if err != nil {
		return fmt.Errorf("create TUN: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	stack, err := singtun.NewGVisor(singtun.StackOptions{
		Context:    ctx,
		Tun:        tunIface,
		TunOptions: tunOpts,
		UDPTimeout: 300,
		Handler:    &tunHandler{session: s, client: c, ipv6Enabled: enableIPv6},
		Logger:     &tunLogger{},
	})
	if err != nil {
		cancel()
		tunIface.Close()
		return fmt.Errorf("create TUN stack: %w", err)
	}

	if err := stack.Start(); err != nil {
		cancel()
		tunIface.Close()
		return fmt.Errorf("start TUN stack: %w", err)
	}

	s.tunIface = tunIface
	s.tunStack = stack
	s.tunCancel = cancel

	log(LogLevelInfo, srcTun, "TUN started (fd=%d, mtu=%d, stack=gvisor, ipv6=%v)", fd, mtu, enableIPv6)
	return nil
}

func (h *tunHandler) rejectIPv6(dest M.Socksaddr) bool {
	return !h.ipv6Enabled && dest.Addr.Is6() && !dest.Addr.Is4In6()
}

func (s *Session) StopTUN() error {
	s.tunMu.Lock()
	iface := s.tunIface
	stack := s.tunStack
	cancel := s.tunCancel
	s.tunIface = nil
	s.tunStack = nil
	s.tunCancel = nil
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

	log(LogLevelInfo, srcTun, "TUN stopped")
	return err
}

func (h *tunHandler) NewConnection(ctx context.Context, conn net.Conn, m M.Metadata) error {
	defer conn.Close()

	if h.rejectIPv6(m.Destination) {
		return fmt.Errorf("IPv6 disabled: %s", m.Destination)
	}

	target := m.Destination.String()
	log(LogLevelDebug, srcTun, "TCP: %s → %s", m.Source, target)

	remote, err := h.client.TCP(target)
	if err != nil {
		if tcpDialErrLimiter.allow(target) {
			log(LogLevelWarn, srcTun, "TCP dial error: %s → %s: %s", m.Source, target, err)
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

func (h *tunHandler) NewPacketConnection(ctx context.Context, conn N.PacketConn, m M.Metadata) error {
	defer conn.Close()

	if h.rejectIPv6(m.Destination) {
		return fmt.Errorf("IPv6 disabled: %s", m.Destination)
	}

	dest := m.Destination.String()
	log(LogLevelDebug, srcTun, "UDP session: %s → %s", m.Source, dest)

	if isDNSPort(dest) {
		return h.handleDNSOverTCP(conn, dest)
	}

	return h.handleUDPRelay(ctx, conn)
}

const maxConcurrentDNS = 16

func (h *tunHandler) handleDNSOverTCP(conn N.PacketConn, defaultDest string) error {
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

		log(LogLevelDebug, srcDNS, "DNS-over-TCP: %s (%d bytes)", dnsAddr, len(query))

		sem <- struct{}{}
		go func() {
			defer func() { <-sem }()

			resp, err := h.session.dnsCache.resolve(h.client, dnsAddr, query, func(tx, rx int) {
				h.session.addTx(tx)
				h.session.addRx(rx)
			})

			var src M.Socksaddr
			if ap, perr := netip.ParseAddrPort(dnsAddr); perr == nil {
				src = M.SocksaddrFromNetIP(ap)
			}

			if err != nil {
				if dnsErrLimiter.allow(dnsAddr) {
					log(LogLevelWarn, srcDNS, "DNS error: %s: %s", dnsAddr, err)
				}
				if sf := buildServFail(query); sf != nil {
					if werr := conn.WritePacket(buf.As(sf), src); werr != nil {
						log(LogLevelDebug, srcDNS, "DNS servfail write error: %s", werr)
					}
				}
				return
			}
			log(LogLevelDebug, srcDNS, "DNS response: %d bytes from %s", len(resp), dnsAddr)
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, srcDNS, "DNS write to local error: %s", werr)
			}
		}()
	}
}

func (h *tunHandler) handleUDPRelay(ctx context.Context, conn N.PacketConn) error {
	rc, err := h.client.UDP()
	if err != nil {
		log(LogLevelWarn, srcTun, "UDP session open failed: %s", err)
		return err
	}
	defer rc.Close()

	done := make(chan struct{}, 2)

	go func() {
		for {
			data, from, err := rc.Receive()
			if err != nil {
				done <- struct{}{}
				return
			}
			h.session.addRx(len(data))
			var dest M.Socksaddr
			if ap, perr := netip.ParseAddrPort(from); perr == nil {
				dest = M.SocksaddrFromNetIP(ap)
			}
			if err := conn.WritePacket(buf.As(data), dest); err != nil {
				done <- struct{}{}
				return
			}
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

func (h *tunHandler) NewError(ctx context.Context, err error) {
	log(LogLevelDebug, srcTun, "Handler error: %s", err)
}

var (
	tcpDialErrLimiter = newRateLimiter(2 * time.Second)
	dnsErrLimiter     = newRateLimiter(2 * time.Second)
)
