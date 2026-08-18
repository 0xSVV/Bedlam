package golib

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"net"
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

	mu      sync.Mutex
	conn    *quic.Conn
	tr      *quic.Transport
	pkt     *hyPacketConn
	udpDown bool
}

func newDoQResolver(c client.Client, server string, base *tls.Config) *doqResolver {
	host, _, _ := net.SplitHostPort(server)
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
	}
}

func (r *doqResolver) id() string { return "quic|" + r.server }

func (r *doqResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if r.isUDPDown() {
		return r.fallback.exchange(ctx, query)
	}
	resp, err := r.exchangeOnce(ctx, query)
	if err == nil {
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
	if ctx.Err() != nil {
		return nil, err
	}
	return r.exchangeOnce(ctx, query)
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
		r.drop(conn)
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
	r.mu.Unlock()

	udp, err := r.client.UDP()
	if err != nil {
		return nil, err
	}
	pkt := newHyPacketConn(udp, r.server)
	tr := &quic.Transport{Conn: pkt}
	qc, err := tr.Dial(ctx, pkt.RemoteAddr(), r.tlsCfg, r.qcfg)
	if err != nil {
		_ = tr.Close()
		_ = pkt.Close()
		return nil, err
	}

	r.mu.Lock()
	if r.conn != nil {
		winner := r.conn
		r.mu.Unlock()
		_ = qc.CloseWithError(0, "")
		_ = tr.Close()
		_ = pkt.Close()
		return winner, nil
	}
	r.conn, r.tr, r.pkt = qc, tr, pkt
	r.mu.Unlock()
	return qc, nil
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
	conn, tr, pkt := r.conn, r.tr, r.pkt
	r.conn, r.tr, r.pkt = nil, nil, nil
	r.mu.Unlock()
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
