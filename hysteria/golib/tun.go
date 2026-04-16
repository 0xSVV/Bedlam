package golib

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"

	singtun "github.com/apernet/sing-tun"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var (
	tunMu           sync.Mutex
	activeTunIface  singtun.Tun
	activeTunCancel context.CancelFunc
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

	tunOpts := singtun.Options{
		FileDescriptor: int(fd),
		MTU:            uint32(mtu),
		Inet4Address:   []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
	}

	tunIface, err := singtun.New(tunOpts)
	if err != nil {
		return fmt.Errorf("create TUN: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	stack, err := singtun.NewSystem(singtun.StackOptions{
		Context:    ctx,
		Tun:        tunIface,
		TunOptions: tunOpts,
		UDPTimeout: 300, // seconds
		Handler:    &tunHandler{client: c},
		Logger:     &tunLogger{},
	})
	if err != nil {
		cancel()
		tunIface.Close()
		return fmt.Errorf("create TUN stack: %w", err)
	}

	activeTunIface = tunIface
	activeTunCancel = cancel

	go func() {
		err := stack.(singtun.StackRunner).Run()
		if err != nil {
			log(LogLevelError, "TUN stack stopped: %s", err.Error())
		}
	}()

	log(LogLevelInfo, "TUN started (fd=%d, mtu=%d)", fd, mtu)
	return nil
}

func StopTUN() error {
	tunMu.Lock()
	defer tunMu.Unlock()

	if activeTunIface == nil {
		return fmt.Errorf("TUN not running")
	}

	if activeTunCancel != nil {
		activeTunCancel()
		activeTunCancel = nil
	}

	err := activeTunIface.Close()
	activeTunIface = nil

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

func (h *tunHandler) handleUDPRelay(ctx context.Context, conn N.PacketConn) error {
	rc, err := h.client.UDP()
	if err != nil {
		log(LogLevelError, "TUN UDP session open failed: %s", err)
		return err
	}
	defer rc.Close()

	done := make(chan struct{}, 2)

	// Remote → Local
	go func() {
		for {
			data, from, err := rc.Receive()
			if err != nil {
				done <- struct{}{}
				return
			}
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

	// Local → Remote
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
			err = rc.Send(buffer.Bytes(), dest.String())
			if err != nil {
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
