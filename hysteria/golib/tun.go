package golib

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"

	"github.com/apernet/hysteria/core/v2/client"
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

	clientMu.Lock()
	c := activeClient
	clientMu.Unlock()

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
			logMsg(LogLevelError, "TUN stack stopped: %s", err.Error())
		}
	}()

	logMsg(LogLevelInfo, "TUN started (fd=%d, mtu=%d)", fd, mtu)
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

	logMsg(LogLevelInfo, "TUN stopped")
	return err
}

type tunLogger struct{}

func (l *tunLogger) Trace(args ...any) { logMsg(LogLevelDebug, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Debug(args ...any) { logMsg(LogLevelDebug, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Info(args ...any)  { logMsg(LogLevelInfo, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Warn(args ...any)  { logMsg(LogLevelWarn, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Error(args ...any) { logMsg(LogLevelError, "TUN stack: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Fatal(args ...any) { logMsg(LogLevelError, "TUN stack fatal: %v", fmt.Sprint(args...)) }
func (l *tunLogger) Panic(args ...any) { logMsg(LogLevelError, "TUN stack panic: %v", fmt.Sprint(args...)) }

type tunHandler struct {
	client client.Client
}

func (h *tunHandler) NewConnection(ctx context.Context, conn net.Conn, m M.Metadata) error {
	defer conn.Close()

	target := m.Destination.String()
	logMsg(LogLevelDebug, "TUN TCP: %s → %s", m.Source, target)

	remote, err := h.client.TCP(target)
	if err != nil {
		logMsg(LogLevelWarn, "TUN TCP dial error: %s → %s: %s", m.Source, target, err)
		return err
	}
	defer remote.Close()

	done := make(chan struct{}, 1)
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

	logMsg(LogLevelInfo, "TUN UDP session: %s → %s", m.Source, m.Destination)

	rc, err := h.client.UDP()
	if err != nil {
		logMsg(LogLevelError, "TUN UDP session open failed: %s", err)
		return err
	}
	defer rc.Close()

	done := make(chan struct{}, 1)

	go func() {
		for {
			data, from, err := rc.Receive()
			if err != nil {
				logMsg(LogLevelDebug, "TUN UDP remote recv error: %s", err)
				done <- struct{}{}
				return
			}
			logMsg(LogLevelDebug, "TUN UDP remote → local: %d bytes from %s", len(data), from)
			var dest M.Socksaddr
			if ap, perr := netip.ParseAddrPort(from); perr == nil {
				dest = M.SocksaddrFromNetIP(ap)
			}
			if err := conn.WritePacket(buf.As(data), dest); err != nil {
				logMsg(LogLevelDebug, "TUN UDP write to local error: %s", err)
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
				logMsg(LogLevelDebug, "TUN UDP local read error: %s", err)
				done <- struct{}{}
				return
			}
			logMsg(LogLevelDebug, "TUN UDP local → remote: %d bytes to %s", buffer.Len(), dest.String())
			err = rc.Send(buffer.Bytes(), dest.String())
			buffer.Release()
			if err != nil {
				logMsg(LogLevelDebug, "TUN UDP remote send error: %s", err)
				done <- struct{}{}
				return
			}
		}
	}()

	<-done
	return nil
}

func (h *tunHandler) NewError(ctx context.Context, err error) {
	logMsg(LogLevelWarn, "TUN handler error: %s", err)
}
