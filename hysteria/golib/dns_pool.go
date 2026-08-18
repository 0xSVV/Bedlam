package golib

import (
	"context"
	"fmt"
	"net"
	"sync/atomic"
	"time"
)

const (
	dnsPoolSize        = 4
	dnsPoolIdleTimeout = 30 * time.Second
)

type pooledConn struct {
	conn net.Conn
	last time.Time
}

// Without reuse every lookup opens its own tunnel stream, and a page that
// resolves a dozen asset hosts at once outruns Android's own resolver timeout.
type streamPool struct {
	label  string
	dial   func(context.Context) (net.Conn, error)
	idle   chan *pooledConn
	closed atomic.Bool
}

func newStreamPool(label string, dial func(context.Context) (net.Conn, error)) *streamPool {
	return &streamPool{
		label: label,
		dial:  dial,
		idle:  make(chan *pooledConn, dnsPoolSize),
	}
}

func (p *streamPool) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if c := p.take(); c != nil {
		if resp, err := p.exchangeOn(ctx, c, query); err == nil {
			return resp, nil
		} else if ctx.Err() != nil {
			return nil, err
		}
	}
	conn, err := p.dial(ctx)
	if err != nil {
		return nil, err
	}
	return p.exchangeOn(ctx, &pooledConn{conn: conn}, query)
}

func (p *streamPool) exchangeOn(ctx context.Context, c *pooledConn, query []byte) ([]byte, error) {
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(dnsIOTimeout)
	}
	_ = c.conn.SetDeadline(deadline)
	resp, err := dnsStreamExchange(c.conn, query)
	if err != nil {
		_ = c.conn.Close()
		return nil, fmt.Errorf("%s: %w", p.label, err)
	}
	_ = c.conn.SetDeadline(time.Time{})
	c.last = time.Now()
	p.put(c)
	return resp, nil
}

func (p *streamPool) take() *pooledConn {
	for {
		select {
		case c := <-p.idle:
			if time.Since(c.last) > dnsPoolIdleTimeout {
				_ = c.conn.Close()
				continue
			}
			return c
		default:
			return nil
		}
	}
}

func (p *streamPool) put(c *pooledConn) {
	if p.closed.Load() {
		_ = c.conn.Close()
		return
	}
	select {
	case p.idle <- c:
	default:
		_ = c.conn.Close()
	}
}

func (p *streamPool) close() {
	p.closed.Store(true)
	for {
		select {
		case c := <-p.idle:
			_ = c.conn.Close()
		default:
			return
		}
	}
}
