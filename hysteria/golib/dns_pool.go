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
	conn     net.Conn
	opened   time.Time
	last     time.Time
	answered int
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
	pooledFailure := ""
	if c := p.take(); c != nil {
		stream := c.describe()
		resp, err := p.exchangeOn(ctx, c, query)
		if err == nil {
			return resp, nil
		}
		if ctx.Err() != nil {
			return nil, fmt.Errorf("%s: %s: %w", p.label, stream, err)
		}
		pooledFailure = fmt.Sprintf("%s failed: %v; ", stream, err)
	}
	dialStart := time.Now()
	conn, err := p.dial(ctx)
	if err != nil {
		if pooledFailure != "" {
			return nil, fmt.Errorf("%s: %s%w", p.label, pooledFailure, err)
		}
		return nil, err
	}
	c := &pooledConn{conn: conn, opened: time.Now()}
	resp, err := p.exchangeOn(ctx, c, query)
	if err != nil {
		return nil, fmt.Errorf("%s: %snew stream dialed in %s: %w", p.label, pooledFailure, diagDuration(c.opened.Sub(dialStart)), err)
	}
	return resp, nil
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
		return nil, err
	}
	_ = c.conn.SetDeadline(time.Time{})
	c.last = time.Now()
	c.answered++
	p.put(c)
	return resp, nil
}

func (c *pooledConn) describe() string {
	return fmt.Sprintf("pooled stream idle %s (open %s, answered %d)",
		diagDuration(wallSince(c.last)), diagDuration(wallSince(c.opened)), c.answered)
}

func wallSince(t time.Time) time.Duration {
	return time.Now().Round(0).Sub(t.Round(0))
}

func diagDuration(d time.Duration) time.Duration {
	return d.Round(100 * time.Millisecond)
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
