package golib

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
	"github.com/apernet/quic-go"
)

const doqALPN = "doq"

type doqResolver struct {
	client   client.Client
	server   string
	tlsCfg   *tls.Config
	qcfg     *quic.Config
	fallback *tlsResolver
	gate     fallbackGate
	ctx      context.Context
	cancel   context.CancelFunc

	mu      sync.Mutex
	conn    *quic.Conn
	tr      *quic.Transport
	pkt     *hyPacketConn
	dialing *doqDial
	closed  bool
	udpDown bool
	byGate  bool
	lastSeq uint64
}

type doqDial struct {
	done chan struct{}
	conn *quic.Conn
	err  error
}

func newDoQResolver(c client.Client, server string, base *tls.Config) *doqResolver {
	host, _, _ := net.SplitHostPort(server)
	ctx, cancel := context.WithCancel(context.Background())
	return &doqResolver{
		client: c,
		server: server,
		tlsCfg: dnsTLSConfig(base, host, []string{doqALPN}),
		qcfg: &quic.Config{
			MaxIdleTimeout:          60 * time.Second,
			KeepAlivePeriod:         0,
			HandshakeIdleTimeout:    5 * time.Second,
			InitialPacketSize:       1200,
			DisablePathMTUDiscovery: true,
		},
		fallback: newTLSResolver(c, server, base),
		ctx:      ctx,
		cancel:   cancel,
		lastSeq:  sessionSeq(c),
	}
}

func (r *doqResolver) id() string { return "quic|" + r.server }

func (r *doqResolver) syncSession() {
	seq := sessionSeq(r.client)
	r.mu.Lock()
	changed := seq != r.lastSeq
	if changed {
		r.lastSeq = seq
		if r.byGate {
			r.udpDown = false
			r.byGate = false
		}
	}
	r.mu.Unlock()
	if changed {
		r.gate.reset()
	}
}

func (r *doqResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	r.syncSession()
	if r.isUDPDown() {
		return r.fallback.exchange(ctx, query)
	}
	if r.gate.tripped() {
		resp, err := r.fallback.exchange(ctx, query)
		if err == nil {
			r.markUDPDownGate()
			if udpDisabledLimiter.allow(r.server) {
				log(LogLevelWarn, srcDNS, "DoQ to %s times out but DoT answers; staying on DoT", r.server)
			}
			return resp, nil
		}
		r.gate.reset()
		return nil, err
	}
	resp, err := r.exchangeOnce(ctx, query)
	if err == nil {
		r.gate.reset()
		return resp, nil
	}
	var dialErr coreErrs.DialError
	if errors.As(err, &dialErr) {
		r.markUDPDown()
		if udpDisabledLimiter.allow(r.server) {
			log(LogLevelWarn, srcDNS, "UDP relay unavailable (%s); using DoT for %s", dialErr, r.server)
		}
		return r.fallback.exchange(ctx, query)
	}
	if ctx.Err() == nil {
		resp, err = r.exchangeOnce(ctx, query)
		if err == nil {
			r.gate.reset()
			return resp, nil
		}
	}
	if isTimeoutClass(err) {
		r.gate.noteTimeout()
	}
	return nil, err
}

func (r *doqResolver) exchangeOnce(ctx context.Context, query []byte) ([]byte, error) {
	conn, err := r.connection(ctx)
	if err != nil {
		return nil, err
	}
	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		r.drop(conn)
		return nil, err
	}

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(dnsIOTimeout)
	}
	_ = stream.SetDeadline(deadline)

	// RFC 9250: one query per stream, and the message ID travels as zero
	// because QUIC already separates concurrent queries.
	wire := make([]byte, len(query))
	copy(wire, query)
	if len(wire) >= 2 {
		binary.BigEndian.PutUint16(wire[:2], 0)
	}
	if err := writeDNSFrame(stream, wire); err != nil {
		stream.CancelRead(0)
		r.drop(conn)
		return nil, err
	}
	// Closing the send side tells the server the query is complete.
	if err := stream.Close(); err != nil {
		stream.CancelRead(0)
		r.drop(conn)
		return nil, err
	}
	resp, err := readDNSFrame(stream)
	if err != nil {
		stream.CancelRead(0)
		if !errors.Is(err, os.ErrDeadlineExceeded) || conn.Context().Err() != nil {
			r.drop(conn)
		}
		return nil, err
	}
	if len(resp) >= 2 && len(query) >= 2 {
		binary.BigEndian.PutUint16(resp[:2], binary.BigEndian.Uint16(query[:2]))
	}
	return resp, nil
}

func (r *doqResolver) connection(ctx context.Context) (*quic.Conn, error) {
	r.mu.Lock()
	if c := r.conn; c != nil {
		r.mu.Unlock()
		return c, nil
	}
	dial := r.dialing
	if dial == nil {
		dial = &doqDial{done: make(chan struct{})}
		r.dialing = dial
		go r.dial(dial)
	}
	r.mu.Unlock()

	started := time.Now()
	select {
	case <-dial.done:
		return dial.conn, dial.err
	case <-ctx.Done():
		return nil, fmt.Errorf("DoQ connection to %s not ready after %s: %w", r.server, diagDuration(time.Since(started)), ctx.Err())
	}
}

func (r *doqResolver) dial(d *doqDial) {
	defer close(d.done)
	ctx, cancel := context.WithTimeout(r.ctx, dnsOpenTimeout)
	defer cancel()
	conn, tr, pkt, err := r.open(ctx)
	r.mu.Lock()
	defer r.mu.Unlock()
	r.dialing = nil
	if err != nil {
		d.err = err
		return
	}
	if r.closed {
		_ = conn.CloseWithError(0, "")
		_ = tr.Close()
		_ = pkt.Close()
		d.err = net.ErrClosed
		return
	}
	r.conn, r.tr, r.pkt = conn, tr, pkt
	d.conn = conn
}

func (r *doqResolver) open(ctx context.Context) (*quic.Conn, *quic.Transport, *hyPacketConn, error) {
	udp, err := r.client.UDP()
	if err != nil {
		return nil, nil, nil, err
	}
	pkt := newHyPacketConn(udp, r.server)
	tr := &quic.Transport{Conn: pkt}
	qc, err := tr.Dial(ctx, pkt.RemoteAddr(), r.tlsCfg, r.qcfg)
	if err != nil {
		_ = tr.Close()
		_ = pkt.Close()
		return nil, nil, nil, err
	}
	return qc, tr, pkt, nil
}

func (r *doqResolver) drop(c *quic.Conn) {
	r.mu.Lock()
	if r.conn != c {
		r.mu.Unlock()
		return
	}
	conn, tr, pkt := r.conn, r.tr, r.pkt
	r.conn, r.tr, r.pkt = nil, nil, nil
	r.mu.Unlock()
	_ = conn.CloseWithError(0, "")
	_ = tr.Close()
	_ = pkt.Close()
}

func (r *doqResolver) close() {
	r.mu.Lock()
	r.closed = true
	conn, tr, pkt := r.conn, r.tr, r.pkt
	r.conn, r.tr, r.pkt = nil, nil, nil
	r.mu.Unlock()
	r.cancel()
	if conn != nil {
		_ = conn.CloseWithError(0, "")
		_ = tr.Close()
		_ = pkt.Close()
	}
	r.fallback.close()
}

func (r *doqResolver) isUDPDown() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.udpDown
}

func (r *doqResolver) markUDPDown() {
	r.mu.Lock()
	r.udpDown = true
	r.mu.Unlock()
}

func (r *doqResolver) markUDPDownGate() {
	r.mu.Lock()
	r.udpDown = true
	r.byGate = true
	r.mu.Unlock()
}
