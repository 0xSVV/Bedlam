package golib

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

const (
	dnsPoolSize        = 4
	dnsPoolIdleTimeout = 30 * time.Second
	dnsOpenTimeout     = 8 * time.Second
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
	label       string
	dial        func(context.Context) (net.Conn, error)
	idle        chan *pooledConn
	openTimeout time.Duration
	ctx         context.Context
	cancel      context.CancelFunc
	closed      atomic.Bool
}

type streamResult struct {
	conn     *pooledConn
	pooled   bool
	resp     []byte
	reusable bool
	stream   string
	err      error
}

type openResult struct {
	conn *pooledConn
	took time.Duration
	err  error
}

const (
	flightPending int32 = iota
	flightDelivered
	flightAbandoned
)

type flight[T any] struct {
	done  chan T
	state atomic.Int32
}

func newFlight[T any]() *flight[T] {
	return &flight[T]{done: make(chan T, 1)}
}

func (f *flight[T]) deliver(v T) bool {
	if !f.state.CompareAndSwap(flightPending, flightDelivered) {
		return false
	}
	f.done <- v
	return true
}

func (f *flight[T]) await(ctx context.Context) (T, bool) {
	select {
	case v := <-f.done:
		return v, true
	case <-ctx.Done():
		if f.state.CompareAndSwap(flightPending, flightAbandoned) {
			var none T
			return none, false
		}
		return <-f.done, true
	}
}

func newStreamPool(label string, dial func(context.Context) (net.Conn, error)) *streamPool {
	ctx, cancel := context.WithCancel(context.Background())
	return &streamPool{
		label:       label,
		dial:        dial,
		idle:        make(chan *pooledConn, dnsPoolSize),
		openTimeout: dnsOpenTimeout,
		ctx:         ctx,
		cancel:      cancel,
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
		if ctx.Err() != nil || deadlineExpired(failures[len(failures)-1].err) {
			return nil, p.failed(failures)
		}
	}
	result := p.exchangeOnNewStream(ctx, query)
	if result.err != nil {
		return nil, p.failed(append(failures, result))
	}
	if result.reusable {
		p.put(result.conn)
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
		resp, reusable, err := p.exchangeOn(streamCtx, pooled, query)
		results <- streamResult{conn: pooled, pooled: true, resp: resp, reusable: reusable, stream: stream, err: err}
	}()
	hedge := time.NewTimer(hedgeDelay(ctx))
	defer hedge.Stop()
	var failures []streamResult
	pooledPending := true
	for running := 1; running > 0; {
		select {
		case <-hedge.C:
			running++
			go func() { results <- p.exchangeOnNewStream(streamCtx, query) }()
		case result := <-results:
			running--
			if result.pooled {
				pooledPending = false
			}
			if result.err != nil {
				if result.pooled {
					failures = append([]streamResult{result}, failures...)
				} else {
					failures = append(failures, result)
				}
				continue
			}
			if !result.pooled && (pooledPending || isTimeoutClass(failures[0].err)) {
				p.drain()
			}
			if result.reusable {
				p.put(result.conn)
			}
			if running > 0 {
				go p.reclaim(results, running)
			}
			return result.resp, nil
		}
	}
	return nil, failures
}

func (p *streamPool) exchangeOnNewStream(ctx context.Context, query []byte) streamResult {
	started := time.Now()
	opened, ok := p.awaitOpen(ctx)
	if !ok {
		return streamResult{stream: "new stream not open after " + diagDuration(time.Since(started)).String(), err: ctx.Err()}
	}
	if opened.err != nil {
		return streamResult{err: opened.err}
	}
	resp, reusable, err := p.exchangeOn(ctx, opened.conn, query)
	if err != nil {
		return streamResult{stream: "new stream dialed in " + diagDuration(opened.took).String(), err: err}
	}
	return streamResult{conn: opened.conn, resp: resp, reusable: reusable}
}

func (p *streamPool) awaitOpen(ctx context.Context) (openResult, bool) {
	opened, ok := p.open().await(ctx)
	if ok && opened.conn != nil && ctx.Err() != nil {
		p.put(opened.conn)
		return openResult{}, false
	}
	return opened, ok
}

func (p *streamPool) open() *flight[openResult] {
	opening := newFlight[openResult]()
	go func() {
		ctx, cancel := context.WithTimeout(p.ctx, p.openTimeout)
		defer cancel()
		started := time.Now()
		conn, err := p.dial(ctx)
		if err != nil {
			opening.deliver(openResult{err: err})
			return
		}
		now := time.Now()
		c := &pooledConn{conn: conn, opened: now, last: now}
		if !opening.deliver(openResult{conn: c, took: now.Sub(started)}) {
			p.put(c)
		}
	}()
	return opening
}

func (p *streamPool) reclaim(results <-chan streamResult, pending int) {
	for ; pending > 0; pending-- {
		if result := <-results; result.err == nil && result.reusable {
			p.put(result.conn)
		}
	}
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

func deadlineExpired(err error) bool {
	return errors.Is(err, os.ErrDeadlineExceeded) || errors.Is(err, context.DeadlineExceeded)
}

func hedgeDelay(ctx context.Context) time.Duration {
	deadline, ok := ctx.Deadline()
	if !ok {
		return dnsIOTimeout / 2
	}
	return time.Until(deadline) / 2
}

func (p *streamPool) exchangeOn(ctx context.Context, c *pooledConn, query []byte) ([]byte, bool, error) {
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(dnsIOTimeout)
	}
	_ = c.conn.SetDeadline(deadline)
	stopInterrupt := context.AfterFunc(ctx, func() { _ = c.conn.SetDeadline(time.Now()) })
	resp, err := dnsStreamExchange(c.conn, query)
	interrupted := !stopInterrupt()
	if err != nil || interrupted {
		_ = c.conn.Close()
	}
	if err != nil {
		if errors.Is(ctx.Err(), context.Canceled) {
			return nil, false, ctx.Err()
		}
		return nil, false, err
	}
	if interrupted {
		return resp, false, nil
	}
	_ = c.conn.SetDeadline(time.Time{})
	c.last = time.Now()
	c.answered++
	return resp, true, nil
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
		if p.closed.Load() {
			p.drain()
		}
	default:
		_ = c.conn.Close()
	}
}

func (p *streamPool) close() {
	p.closed.Store(true)
	p.cancel()
	p.drain()
}

func (p *streamPool) drain() {
	for {
		select {
		case c := <-p.idle:
			_ = c.conn.Close()
		default:
			return
		}
	}
}
