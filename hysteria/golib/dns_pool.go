package golib

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

const (
	dnsPoolSize         = 4
	dnsPoolIdleTimeout  = 30 * time.Second
	dnsOpenTimeout      = 8 * time.Second
	dnsPoolMaxOpening   = 2
	dnsLateReadTimeout  = 8 * time.Second
	dnsStreamMaxQueries = 32
	dnsStreamStall      = dnsAttemptTimeout / 2
	dnsStreamRetries    = 2
	dnsSerialHold       = time.Minute

	dnsResponseFlag = 0x80
	dnsOpcodeMask   = 0x78
)

var errStreamStalled = errors.New("the stream stopped answering: another query on it timed out")

type pooledConn struct {
	conn     net.Conn
	opened   time.Time
	last     time.Time
	answered int
	users    int
	joined   bool

	writeMu   sync.Mutex
	mu        sync.Mutex
	pending   map[uint16]*pendingQuery
	reading   bool
	proven    bool
	pipelined bool
	err       error
}

type pendingQuery struct {
	wireID   uint16
	origID   uint16
	opcode   byte
	question string
	sent     time.Time
	before   int
	deadline time.Time
	flight   *flight[streamResult]
	late     func([]byte)
	stream   string
	pooled   bool
}

// Without reuse every lookup opens its own tunnel stream, and a page that
// resolves a dozen asset hosts at once outruns Android's own resolver timeout.
type streamPool struct {
	label       string
	dial        func(context.Context) (net.Conn, error)
	idle        chan *pooledConn
	opening     chan struct{}
	openTimeout time.Duration
	lateRead    time.Duration
	stall       time.Duration
	ctx         context.Context
	cancel      context.CancelFunc
	closed      atomic.Bool

	mu          sync.Mutex
	busy        map[*pooledConn]struct{}
	shareable   chan struct{}
	serialUntil time.Time
}

type streamResult struct {
	conn   *pooledConn
	pooled bool
	resp   []byte
	stream string
	err    error
	lost   bool
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
		opening:     make(chan struct{}, dnsPoolMaxOpening),
		openTimeout: dnsOpenTimeout,
		lateRead:    dnsLateReadTimeout,
		stall:       dnsStreamStall,
		ctx:         ctx,
		cancel:      cancel,
	}
}

func (p *streamPool) exchange(ctx context.Context, callerQuery []byte) ([]byte, error) {
	if len(callerQuery) < dnsHeaderLen {
		return nil, fmt.Errorf("%w: query shorter than a DNS header", errDNSMalformed)
	}
	query := bytes.Clone(callerQuery)
	started := time.Now()
	c, shared, err := p.takeOrReserveOpen(ctx)
	if err != nil {
		return nil, p.failed([]streamResult{notOpenSince(started, err)})
	}
	var failures []streamResult
	if c != nil {
		resp, pooledFailures := p.exchangeOnPooled(ctx, c, shared, query)
		if pooledFailures == nil {
			return resp, nil
		}
		failures = pooledFailures
		if ctx.Err() != nil || deadlineExpired(failures[len(failures)-1].err) {
			return nil, p.failed(failures)
		}
	} else {
		result := p.exchangeOnNewStream(ctx, query, true)
		if result.err == nil {
			p.release(result.conn)
			return result.resp, nil
		}
		failures = []streamResult{result}
		if !p.retryable(ctx, result) {
			return nil, p.failed(failures)
		}
	}
	for retries := 1; ; retries++ {
		retried := time.Now()
		c, err := p.reserveOpenOrShare(ctx)
		if err != nil {
			return nil, p.failed(append(failures, notOpenSince(retried, err)))
		}
		var result streamResult
		if c != nil {
			result = p.exchangeOn(ctx, c, query, c.describeShared(), true)
		} else {
			result = p.exchangeOnNewStream(ctx, query, true)
		}
		if result.err == nil {
			p.release(result.conn)
			return result.resp, nil
		}
		failures = append(failures, result)
		if retries == dnsStreamRetries || !p.retryable(ctx, result) {
			return nil, p.failed(failures)
		}
	}
}

func (p *streamPool) retryable(ctx context.Context, result streamResult) bool {
	return result.lost && ctx.Err() == nil && !p.closed.Load()
}

func (p *streamPool) exchangeOnPooled(ctx context.Context, pooled *pooledConn, shared bool, query []byte) ([]byte, []streamResult) {
	streamCtx, stopStreams := context.WithCancel(ctx)
	defer stopStreams()
	results := make(chan streamResult, 2)
	stream := pooled.describe()
	if shared {
		stream = pooled.describeShared()
	}
	go func() { results <- p.exchangeOn(streamCtx, pooled, query, stream, true) }()
	hedge := time.NewTimer(hedgeDelay(ctx))
	defer hedge.Stop()
	var failures []streamResult
	pooledPending := true
	for running := 1; running > 0; {
		select {
		case <-hedge.C:
			running++
			go func() { results <- p.exchangeOnNewStream(streamCtx, query, false) }()
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
			p.release(result.conn)
			if running > 0 {
				go p.reclaim(results, running)
			}
			return result.resp, nil
		}
	}
	return nil, failures
}

func (p *streamPool) exchangeOnNewStream(ctx context.Context, query []byte, reserved bool) streamResult {
	started := time.Now()
	if !reserved {
		if err := p.reserveOpen(ctx); err != nil {
			return notOpenSince(started, err)
		}
	}
	opened, ok := p.awaitOpen(ctx)
	if !ok {
		return notOpenSince(started, ctx.Err())
	}
	if opened.err != nil {
		return streamResult{err: opened.err}
	}
	p.acquire(opened.conn, true)
	return p.exchangeOn(ctx, opened.conn, query, "new stream dialed in "+diagDuration(opened.took).String(), false)
}

func notOpenSince(started time.Time, err error) streamResult {
	return streamResult{stream: "new stream not open after " + diagDuration(time.Since(started)).String(), err: err}
}

func (p *streamPool) takeOrReserveOpen(ctx context.Context) (c *pooledConn, shared bool, err error) {
	for {
		shareable := p.shareSignal()
		if c := p.take(); c != nil {
			return c, false, nil
		}
		if err := ctx.Err(); err != nil {
			return nil, false, err
		}
		if p.tryReserveOpen() {
			return nil, false, nil
		}
		if c := p.share(); c != nil {
			return c, true, nil
		}
		select {
		case c := <-p.idle:
			if !p.closeIfStale(c) {
				p.acquire(c, false)
				return c, false, nil
			}
		case p.opening <- struct{}{}:
			return nil, false, nil
		case <-shareable:
		case <-ctx.Done():
			return nil, false, ctx.Err()
		}
	}
}

func (p *streamPool) reserveOpenOrShare(ctx context.Context) (*pooledConn, error) {
	for {
		shareable := p.shareSignal()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if p.tryReserveOpen() {
			return nil, nil
		}
		if c := p.share(); c != nil {
			return c, nil
		}
		select {
		case p.opening <- struct{}{}:
			return nil, nil
		case <-shareable:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

func (p *streamPool) tryReserveOpen() bool {
	select {
	case p.opening <- struct{}{}:
		return true
	default:
		return false
	}
}

func (p *streamPool) reserveOpen(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case p.opening <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
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
		defer func() { <-p.opening }()
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
		if result := <-results; result.err == nil {
			p.release(result.conn)
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
	delay := dnsAttemptTimeout / 2
	if deadline, ok := ctx.Deadline(); ok {
		delay = min(delay, time.Until(deadline)/2)
	}
	return delay
}

func (p *streamPool) exchangeOn(ctx context.Context, c *pooledConn, query []byte, stream string, pooled bool) streamResult {
	sent := time.Now()
	result, ok := p.send(ctx, c, query, stream, pooled).await(ctx)
	if !ok {
		return streamResult{pooled: pooled, stream: stream, err: fmt.Errorf("no response after %s: %w", diagDuration(time.Since(sent)), ctx.Err())}
	}
	return result
}

func (p *streamPool) send(ctx context.Context, c *pooledConn, query []byte, stream string, pooled bool) *flight[streamResult] {
	question, _ := dnsQuestion(query)
	q := &pendingQuery{
		origID:   binary.BigEndian.Uint16(query[:2]),
		opcode:   query[2] & dnsOpcodeMask,
		question: question,
		deadline: lateReadDeadline(ctx, p.lateRead),
		flight:   newFlight[streamResult](),
		late:     lateAnswer(ctx),
		stream:   stream,
		pooled:   pooled,
	}
	go p.write(c, q, query)
	return q.flight
}

func (p *streamPool) write(c *pooledConn, q *pendingQuery, query []byte) {
	wire, err := p.register(c, q, query)
	if err != nil {
		q.flight.deliver(streamResult{pooled: q.pooled, stream: q.stream, err: err, lost: true})
		return
	}
	p.signalShare()
	c.writeMu.Lock()
	err = writeDNSFrame(c.conn, wire)
	c.writeMu.Unlock()
	if err != nil {
		p.fail(c, fmt.Errorf("write query: %w", err))
	}
}

func (p *streamPool) register(c *pooledConn, q *pendingQuery, query []byte) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err != nil {
		return nil, c.err
	}
	if c.pending == nil {
		c.pending = map[uint16]*pendingQuery{}
	}
	id := q.origID
	for c.pending[id] != nil {
		id++
	}
	q.wireID, q.sent, q.before = id, time.Now(), c.answered
	c.pending[id] = q
	_ = c.conn.SetDeadline(c.earliestDeadlineLocked())
	if !c.reading {
		c.reading = true
		go p.read(c)
	}
	wire := bytes.Clone(query)
	binary.BigEndian.PutUint16(wire[:2], id)
	return wire, nil
}

func (p *streamPool) read(c *pooledConn) {
	for {
		resp, err := readDNSFrame(c.conn)
		if err != nil {
			p.fail(c, err)
			return
		}
		if !p.dispatch(c, resp) {
			return
		}
	}
}

func (p *streamPool) dispatch(c *pooledConn, resp []byte) bool {
	c.mu.Lock()
	q := c.pending[binary.BigEndian.Uint16(resp[:2])]
	if q == nil {
		c.mu.Unlock()
		p.fail(c, fmt.Errorf("%w: response transaction ID mismatch", errDNSMalformed))
		return false
	}
	if !q.answeredBy(resp) {
		c.mu.Unlock()
		p.fail(c, fmt.Errorf("%w: response does not answer the question sent under its ID", errDNSMalformed))
		return false
	}
	delete(c.pending, q.wireID)
	c.last = time.Now()
	c.pipelined = c.pipelined || c.answered > q.before
	c.answered++
	c.proven = true
	more := len(c.pending) > 0
	if more {
		_ = c.conn.SetDeadline(c.earliestDeadlineLocked())
	} else {
		c.reading = false
		_ = c.conn.SetDeadline(time.Time{})
	}
	c.mu.Unlock()
	binary.BigEndian.PutUint16(resp[:2], q.origID)
	if !q.flight.deliver(streamResult{conn: c, pooled: q.pooled, resp: resp, stream: q.stream}) {
		p.release(c)
		if !p.closed.Load() {
			q.late(resp)
		}
	}
	p.signalShare()
	return more
}

func (q *pendingQuery) answeredBy(resp []byte) bool {
	if resp[2]&dnsResponseFlag == 0 || resp[2]&dnsOpcodeMask != q.opcode {
		return false
	}
	if q.question == "" || binary.BigEndian.Uint16(resp[4:6]) == 0 {
		return true
	}
	question, ok := dnsQuestion(resp)
	return ok && question == q.question
}

func (p *streamPool) fail(c *pooledConn, cause error) {
	c.mu.Lock()
	if c.err != nil {
		c.mu.Unlock()
		return
	}
	c.err = cause
	pending := c.pending
	c.pending = nil
	oneAnswer := !c.pipelined && c.answered <= 1
	c.mu.Unlock()
	_ = c.conn.Close()
	var dialErr coreErrs.DialError
	unreached := errors.As(cause, &dialErr)
	now := time.Now()
	p.mu.Lock()
	delete(p.busy, c)
	if c.joined && oneAnswer && len(pending) > 0 && !unreached && !deadlineExpired(cause) && !isTunnelFailure(cause) {
		p.serialUntil = now.Add(dnsSerialHold)
	}
	p.mu.Unlock()
	expired := false
	for _, q := range pending {
		expired = expired || !now.Before(q.deadline)
	}
	for _, q := range pending {
		err := cause
		if expired && deadlineExpired(cause) && now.Before(q.deadline) {
			err = errStreamStalled
		}
		q.flight.deliver(streamResult{pooled: q.pooled, stream: q.stream, err: err, lost: !unreached && !deadlineExpired(err)})
	}
}

func (c *pooledConn) earliestDeadlineLocked() time.Time {
	var earliest time.Time
	for _, q := range c.pending {
		if earliest.IsZero() || q.deadline.Before(earliest) {
			earliest = q.deadline
		}
	}
	return earliest
}

func (c *pooledConn) shareable(now time.Time, stall time.Duration) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err != nil || !c.proven {
		return false
	}
	for _, q := range c.pending {
		if now.Sub(q.sent) > stall && !c.last.After(q.sent) {
			return false
		}
	}
	return true
}

func (p *streamPool) acquire(c *pooledConn, proven bool) {
	c.mu.Lock()
	c.proven = proven
	c.mu.Unlock()
	p.mu.Lock()
	c.users = 1
	closed := p.closed.Load()
	if !closed {
		if p.busy == nil {
			p.busy = map[*pooledConn]struct{}{}
		}
		p.busy[c] = struct{}{}
	}
	p.mu.Unlock()
	if closed {
		p.fail(c, net.ErrClosed)
	}
}

func (p *streamPool) share() *pooledConn {
	now := time.Now()
	p.mu.Lock()
	defer p.mu.Unlock()
	if now.Before(p.serialUntil) {
		return nil
	}
	var best *pooledConn
	for c := range p.busy {
		if c.users < dnsStreamMaxQueries && (best == nil || c.users < best.users) && c.shareable(now, p.stall) {
			best = c
		}
	}
	if best != nil {
		best.users++
		best.joined = true
	}
	return best
}

func (p *streamPool) release(c *pooledConn) {
	p.mu.Lock()
	c.users--
	idle := c.users <= 0
	if idle {
		delete(p.busy, c)
	}
	p.mu.Unlock()
	if !idle {
		return
	}
	c.mu.Lock()
	alive := c.err == nil
	c.mu.Unlock()
	if alive {
		p.put(c)
	}
}

func (p *streamPool) shareSignal() <-chan struct{} {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.shareable == nil {
		p.shareable = make(chan struct{})
	}
	return p.shareable
}

func (p *streamPool) signalShare() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.shareable != nil {
		close(p.shareable)
		p.shareable = nil
	}
}

func lateReadDeadline(ctx context.Context, lateRead time.Duration) time.Time {
	deadline := time.Now().Add(lateRead)
	if callerDeadline, ok := ctx.Deadline(); ok && callerDeadline.After(deadline) {
		return callerDeadline
	}
	return deadline
}

func (c *pooledConn) describe() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return fmt.Sprintf("pooled stream idle %s (open %s, answered %d)",
		diagDuration(wallSince(c.last)), diagDuration(wallSince(c.opened)), c.answered)
}

func (c *pooledConn) describeShared() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return fmt.Sprintf("shared stream (open %s, answered %d, %d waiting)",
		diagDuration(wallSince(c.opened)), c.answered, len(c.pending))
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
			if !p.closeIfStale(c) {
				p.acquire(c, false)
				return c
			}
		default:
			return nil
		}
	}
}

func (p *streamPool) closeIfStale(c *pooledConn) bool {
	if time.Since(c.last) > dnsPoolIdleTimeout {
		_ = c.conn.Close()
		return true
	}
	return false
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
	p.mu.Lock()
	busy := p.busy
	p.busy = nil
	p.mu.Unlock()
	for c := range busy {
		p.fail(c, net.ErrClosed)
	}
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
