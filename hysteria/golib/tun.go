package golib

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"

	singtun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var (
	tunMu            sync.Mutex
	activeTunIface   singtun.Tun
	activeTunStack   singtun.Stack
	activeTunCancel  context.CancelFunc
	activeTunHandler *tunHandler
)

func StartTUN(fd int32, mtu int32) error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if activeTunIface != nil {
		return fmt.Errorf("TUN already running")
	}

	clientMutex.Lock()
	c := activeClient
	clientMutex.Unlock()

	if c == nil {
		return fmt.Errorf("client not connected")
	}

	if mtu <= 0 {
		mtu = 1500
	}

	handler := &tunHandler{client: c}

	tunOpts := singtun.Options{
		FileDescriptor: int(fd),
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		Inet6Address:   []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")},
	}

	tunIface, err := singtun.New(tunOpts)
	if err != nil {
		return fmt.Errorf("create TUN: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	// gVisor stack keeps TCP/UDP handling in userspace so we don't need
	// to bind host listeners on TUN-assigned addresses — in particular
	// avoids "listen tcp6 [...]: cannot assign requested address" that
	// the system stack hits on Android after a network handoff.
	stack, err := singtun.NewGVisor(singtun.StackOptions{
		Context:    ctx,
		Tun:        tunIface,
		TunOptions: tunOpts,
		UDPTimeout: 300, // seconds
		Handler:    handler,
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

	activeTunIface = tunIface
	activeTunStack = stack
	activeTunCancel = cancel
	activeTunHandler = handler

	log(LogLevelInfo, "TUN started (fd=%d, mtu=%d, stack=gvisor)", fd, mtu)
	return nil
}

func StopTUN() error {
	tunMu.Lock()
	iface := activeTunIface
	stack := activeTunStack
	cancel := activeTunCancel
	handler := activeTunHandler
	activeTunIface = nil
	activeTunStack = nil
	activeTunCancel = nil
	activeTunHandler = nil
	tunMu.Unlock()

	if iface == nil {
		return fmt.Errorf("TUN not running")
	}

	if cancel != nil {
		cancel()
	}
	if handler != nil {
		handler.closeMux()
	}
	if stack != nil {
		_ = stack.Close()
	}
	err := iface.Close()

	log(LogLevelInfo, "TUN stopped")
	return err
}

func (h *tunHandler) NewConnection(ctx context.Context, conn net.Conn, m M.Metadata) error {
	defer conn.Close()

	target := m.Destination.String()
	log(LogLevelDebug, "TUN TCP: %s → %s", m.Source, target)

	remote, err := h.client.TCP(target)
	if err != nil {
		log(LogLevelWarn, "TUN TCP dial error: %s → %s: %s", m.Source, target, err)
		return err
	}
	defer remote.Close()

	done := make(chan struct{}, 2)
	go func() {
		io.Copy(remote, conn)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(conn, remote)
		done <- struct{}{}
	}()
	<-done
	return nil
}

func (h *tunHandler) NewPacketConnection(ctx context.Context, conn N.PacketConn, m M.Metadata) error {
	defer conn.Close()

	dest := m.Destination.String()
	log(LogLevelDebug, "TUN UDP session: %s → %s", m.Source, dest)

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

		log(LogLevelDebug, "TUN DNS-over-TCP: %s (%d bytes)", dnsAddr, len(query))

		sem <- struct{}{}
		go func() {
			defer func() { <-sem }()

			resp, err := globalDNSCache.resolve(h.client, dnsAddr, query)
			if err != nil {
				log(LogLevelWarn, "TUN DNS error: %s: %s", dnsAddr, err)
				return
			}
			log(LogLevelDebug, "TUN DNS response: %d bytes from %s", len(resp), dnsAddr)

			var src M.Socksaddr
			if ap, perr := netip.ParseAddrPort(dnsAddr); perr == nil {
				src = M.SocksaddrFromNetIP(ap)
			}
			if werr := conn.WritePacket(buf.As(resp), src); werr != nil {
				log(LogLevelDebug, "TUN DNS write to local error: %s", werr)
			}
		}()
	}
}

func (h *tunHandler) getMux() (*udpMux, error) {
	h.muxMu.Lock()
	defer h.muxMu.Unlock()
	if h.mux != nil && !h.mux.isDead() {
		return h.mux, nil
	}
	if h.mux != nil {
		h.mux.close()
		h.mux = nil
	}
	m, err := newUDPMux(h.client)
	if err != nil {
		return nil, err
	}
	h.mux = m
	return m, nil
}


func resetTunMux() {
	tunMu.Lock()
	h := activeTunHandler
	tunMu.Unlock()
	if h != nil {
		h.closeMux()
	}
}

func (h *tunHandler) closeMux() {
	h.muxMu.Lock()
	m := h.mux
	h.mux = nil
	h.muxMu.Unlock()
	if m != nil {
		m.close()
	}
}

func (h *tunHandler) handleUDPRelay(ctx context.Context, conn N.PacketConn) error {
	mux, err := h.getMux()
	if err != nil {
		log(LogLevelError, "TUN UDP session open failed: %s", err)
		return err
	}
	defer mux.unregister(conn)

	done := make(chan struct{}, 1)

	// Local → Remote (replies dispatched by shared mux reader)
	go func() {
		buffer := buf.NewPacket()
		defer buffer.Release()
		for {
			buffer.Reset()
			dest, err := conn.ReadPacket(buffer)
			if err != nil {
				done <- struct{}{}
				return
			}
			if err := mux.send(buffer.Bytes(), dest.String(), conn); err != nil {
				done <- struct{}{}
				return
			}
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
	log(LogLevelWarn, "TUN handler error: %s", err)
}
