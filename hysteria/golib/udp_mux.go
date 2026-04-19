package golib

import (
	"errors"
	"net/netip"
	"sync"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var errMuxClosed = errors.New("udp mux closed")

type udpMux struct {
	mu     sync.RWMutex
	conn   client.HyUDPConn
	flows  map[string]N.PacketConn // remote addr → local packet conn
	closed bool
}

func newUDPMux(c client.Client) (*udpMux, error) {
	conn, err := c.UDP()
	if err != nil {
		return nil, err
	}
	m := &udpMux{
		conn:  conn,
		flows: make(map[string]N.PacketConn),
	}
	go m.readLoop()
	return m, nil
}

func (m *udpMux) readLoop() {
	defer func() {
		// Mark dead so next send forces a fresh mux.
		m.mu.Lock()
		m.closed = true
		m.flows = nil
		m.mu.Unlock()
	}()
	for {
		data, from, err := m.conn.Receive()
		if err != nil {
			log(LogLevelWarn, "UDP mux receive ended: %s (mux will be recreated)", err)
			return
		}
		m.mu.RLock()
		local := m.flows[from]
		m.mu.RUnlock()
		if local == nil {
			continue
		}
		var src M.Socksaddr
		if ap, perr := netip.ParseAddrPort(from); perr == nil {
			src = M.SocksaddrFromNetIP(ap)
		}
		if err := local.WritePacket(buf.As(data), src); err != nil {
			log(LogLevelDebug, "UDP mux local write error: %s", err)
		}
	}
}

func (m *udpMux) send(data []byte, remote string, local N.PacketConn) error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return errMuxClosed
	}
	m.flows[remote] = local
	m.mu.Unlock()
	return m.conn.Send(data, remote)
}

func (m *udpMux) unregister(local N.PacketConn) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for k, v := range m.flows {
		if v == local {
			delete(m.flows, k)
		}
	}
}

func (m *udpMux) isDead() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.closed
}

func (m *udpMux) close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return
	}
	m.closed = true
	m.flows = nil
	_ = m.conn.Close()
}
