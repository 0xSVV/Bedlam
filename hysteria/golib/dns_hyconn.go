package golib

import (
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
)

const hyPacketConnBacklog = 256

type hyAddr struct {
	network string
	addr    string
}

func (a hyAddr) Network() string { return a.network }

func (a hyAddr) String() string { return a.addr }

// hyPacketConn presents one Hysteria UDP session as a connected
// net.PacketConn: every write goes to the configured remote and every
// packet read is reported as coming from it.
type hyPacketConn struct {
	conn   client.HyUDPConn
	remote string
	raddr  net.Addr
	laddr  net.Addr

	sendMu    sync.Mutex
	recvCh    chan []byte
	closeCh   chan struct{}
	closeOnce sync.Once
	closeErr  atomic.Pointer[error]
	readDl    pipeDeadline
}

func newHyPacketConn(conn client.HyUDPConn, remote string) *hyPacketConn {
	p := &hyPacketConn{
		conn:    conn,
		remote:  remote,
		raddr:   hyAddr{"hysteria", remote},
		laddr:   hyAddr{"hysteria", "udp"},
		recvCh:  make(chan []byte, hyPacketConnBacklog),
		closeCh: make(chan struct{}),
		readDl:  makePipeDeadline(),
	}
	go p.pump()
	return p
}

func (p *hyPacketConn) pump() {
	for {
		data, _, err := p.conn.Receive()
		if err != nil {
			p.closeWith(err)
			return
		}
		pkt := make([]byte, len(data))
		copy(pkt, data)
		select {
		case p.recvCh <- pkt:
		case <-p.closeCh:
			return
		default:
		}
	}
}

func (p *hyPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	select {
	case data := <-p.recvCh:
		return copy(b, data), p.raddr, nil
	case <-p.closeCh:
		return 0, nil, p.closedError()
	case <-p.readDl.wait():
		return 0, nil, os.ErrDeadlineExceeded
	}
}

func (p *hyPacketConn) WriteTo(b []byte, _ net.Addr) (int, error) {
	select {
	case <-p.closeCh:
		return 0, net.ErrClosed
	default:
	}
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if err := p.conn.Send(b, p.remote); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (p *hyPacketConn) Close() error {
	p.closeWith(nil)
	return nil
}

func (p *hyPacketConn) closeWith(err error) {
	p.closeOnce.Do(func() {
		if err != nil {
			p.closeErr.Store(&err)
		}
		close(p.closeCh)
		_ = p.conn.Close()
	})
}

func (p *hyPacketConn) closedError() error {
	if e := p.closeErr.Load(); e != nil {
		return *e
	}
	return net.ErrClosed
}

func (p *hyPacketConn) LocalAddr() net.Addr { return p.laddr }

func (p *hyPacketConn) RemoteAddr() net.Addr { return p.raddr }

func (p *hyPacketConn) SetDeadline(t time.Time) error {
	p.readDl.set(t)
	return nil
}

func (p *hyPacketConn) SetReadDeadline(t time.Time) error {
	p.readDl.set(t)
	return nil
}

func (p *hyPacketConn) SetWriteDeadline(time.Time) error { return nil }

type pipeDeadline struct {
	mu     sync.Mutex
	timer  *time.Timer
	cancel chan struct{}
}

func makePipeDeadline() pipeDeadline {
	return pipeDeadline{cancel: make(chan struct{})}
}

func (d *pipeDeadline) set(t time.Time) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if d.timer != nil && !d.timer.Stop() {
		<-d.cancel
	}
	d.timer = nil

	closed := isClosedChan(d.cancel)
	if t.IsZero() {
		if closed {
			d.cancel = make(chan struct{})
		}
		return
	}

	if dur := time.Until(t); dur > 0 {
		if closed {
			d.cancel = make(chan struct{})
		}
		d.timer = time.AfterFunc(dur, func() {
			close(d.cancel)
		})
		return
	}

	if !closed {
		close(d.cancel)
	}
}

func (d *pipeDeadline) wait() chan struct{} {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.cancel
}

func isClosedChan(c <-chan struct{}) bool {
	select {
	case <-c:
		return true
	default:
		return false
	}
}
