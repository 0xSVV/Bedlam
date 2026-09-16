package golib

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"strings"
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

type streamResult struct {
	pooled bool
	resp   []byte
	stream string
	err    error
}

func newStreamPool(label string, dial func(context.Context) (net.Conn, error)) *streamPool {
	return &streamPool{
		label: label,
		dial:  dial,
		idle:  make(chan *pooledConn, dnsPoolSize),
	}
}

func (p *streamPool) exchange(ctx context.Context, query []byte) ([]byte, error) {
	var failures []streamResult
	if c := p.take(); c != nil {
		resp, pooledFailures := p.exchangeOnPooled(ctx, c, query)
		if pooledFailures == nil {
			return resp, nil
		}
		failures = pooledFailures
		if ctx.Err() != nil || isTimeoutClass(failures[len(failures)-1].err) {
			return nil, p.failed(failures)
		}
	}
	result := p.exchangeOnNewStream(ctx, query)
	if result.err != nil {
		return nil, p.failed(append(failures, result))
	}
	return result.resp, nil
}

func (p *streamPool) exchangeOnPooled(ctx context.Context, pooled *pooledConn, callerQuery []byte) ([]byte, []streamResult) {
	query := bytes.Clone(callerQuery)
	streamCtx, stopStreams := context.WithCancel(ctx)
	defer stopStreams()
	results := make(chan streamResult, 2)
	stream := pooled.describe()
	go func() {
		resp, err := p.exchangeOn(streamCtx, pooled, query)
		results <- streamResult{pooled: true, resp: resp, stream: stream, err: err}
	}()
	hedge := time.NewTimer(hedgeDelay(ctx))
	defer hedge.Stop()
	var failures []streamResult
	for running := 1; running > 0; {
		select {
		case <-hedge.C:
			running++
			go func() { results <- p.exchangeOnNewStream(streamCtx, query) }()
		case result := <-results:
			running--
			if result.err == nil {
				return result.resp, nil
			}
			if result.pooled {
				failures = append([]streamResult{result}, failures...)
			} else {
				failures = append(failures, result)
			}
		}
	}
	return nil, failures
}

func (p *streamPool) exchangeOnNewStream(ctx context.Context, query []byte) streamResult {
	dialStart := time.Now()
	conn, err := p.dial(ctx)
	if err != nil {
		return streamResult{err: err}
	}
	c := &pooledConn{conn: conn, opened: time.Now()}
	resp, err := p.exchangeOn(ctx, c, query)
	if err != nil {
		return streamResult{stream: "new stream dialed in " + diagDuration(c.opened.Sub(dialStart)).String(), err: err}
	}
	return streamResult{resp: resp}
}

func (p *streamPool) failed(failures []streamResult) error {
	last := failures[len(failures)-1]
	if len(failures) == 1 && last.stream == "" {
		return last.err
	}
	var earlier strings.Builder
	for _, failure := range failures[:len(failures)-1] {
		if failure.stream != "" {
			earlier.WriteString(failure.stream + " failed: ")
		}
		earlier.WriteString(failure.err.Error() + "; ")
	}
	if last.stream == "" {
		return fmt.Errorf("%s: %s%w", p.label, earlier.String(), last.err)
	}
	return fmt.Errorf("%s: %s%s: %w", p.label, earlier.String(), last.stream, last.err)
}

func hedgeDelay(ctx context.Context) time.Duration {
	deadline, ok := ctx.Deadline()
	if !ok {
		return dnsIOTimeout / 2
	}
	return time.Until(deadline) / 2
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
