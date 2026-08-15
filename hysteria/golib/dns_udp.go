package golib

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"math/rand"
	"net"
	"sync"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	coreErrs "github.com/apernet/hysteria/core/v2/errors"
)

const dnsUDPRetransmit = 1500 * time.Millisecond

var errUDPSessionClosed = errors.New("udp dns session closed")

type udpResolver struct {
	client client.Client
	server string

	mu       sync.Mutex
	conn     client.HyUDPConn
	inflight map[uint16]chan []byte
	closed   bool
	tcpOnly  bool

	sendMu sync.Mutex
}

func newUDPResolver(c client.Client, server string) *udpResolver {
	return &udpResolver{
		client:   c,
		server:   server,
		inflight: map[uint16]chan []byte{},
	}
}

func (r *udpResolver) id() string { return "udp|" + r.server }

func (r *udpResolver) close() {
	r.mu.Lock()
	r.closed = true
	conn := r.conn
	r.conn = nil
	pending := r.inflight
	r.inflight = map[uint16]chan []byte{}
	r.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
	for _, ch := range pending {
		close(ch)
	}
}

func (r *udpResolver) exchange(ctx context.Context, query []byte) ([]byte, error) {
	if len(query) < 12 {
		return nil, fmt.Errorf("%w: query too short", errDNSMalformed)
	}
	if r.isTCPOnly() {
		return dnsOverTCPContext(ctx, r.client, r.server, query)
	}
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		resp, retry, err := r.exchangeOnce(ctx, query)
		if err == nil {
			return resp, nil
		}
		var dialErr coreErrs.DialError
		if errors.As(err, &dialErr) {
			r.markTCPOnly()
			if udpDisabledLimiter.allow(r.server) {
				log(LogLevelWarn, srcDNS, "UDP relay unavailable (%s); using TCP for %s", err, r.server)
			}
			return dnsOverTCPContext(ctx, r.client, r.server, query)
		}
		lastErr = err
		if !retry || ctx.Err() != nil {
			break
		}
	}
	return nil, lastErr
}

func (r *udpResolver) exchangeOnce(ctx context.Context, query []byte) ([]byte, bool, error) {
	conn, err := r.ensureConn()
	if err != nil {
		return nil, false, err
	}
	origID := binary.BigEndian.Uint16(query[:2])
	ch, txID, err := r.register(conn)
	if err != nil {
		return nil, true, err
	}
	defer r.unregister(txID, ch)

	q := make([]byte, len(query))
	copy(q, query)
	binary.BigEndian.PutUint16(q[:2], txID)

	if err := r.send(conn, q); err != nil {
		r.dropConn(conn)
		return nil, true, fmt.Errorf("send DNS query: %w", err)
	}

	retransmit := time.NewTimer(dnsUDPRetransmit)
	defer retransmit.Stop()
	for {
		select {
		case resp, ok := <-ch:
			if !ok {
				return nil, true, errUDPSessionClosed
			}
			out := make([]byte, len(resp))
			copy(out, resp)
			binary.BigEndian.PutUint16(out[:2], origID)
			if len(out) >= 3 && out[2]&0x02 != 0 {
				full, terr := dnsOverTCPContext(ctx, r.client, r.server, query)
				if terr != nil {
					return out, false, nil
				}
				return full, false, nil
			}
			return out, false, nil
		case <-retransmit.C:
			if err := r.send(conn, q); err != nil {
				r.dropConn(conn)
				return nil, true, fmt.Errorf("resend DNS query: %w", err)
			}
		case <-ctx.Done():
			return nil, false, fmt.Errorf("DNS query to %s: %w", r.server, ctx.Err())
		}
	}
}

func (r *udpResolver) ensureConn() (client.HyUDPConn, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.closed {
		return nil, net.ErrClosed
	}
	if r.conn != nil {
		return r.conn, nil
	}
	conn, err := r.client.UDP()
	if err != nil {
		return nil, err
	}
	r.conn = conn
	go r.recvLoop(conn)
	return conn, nil
}

func (r *udpResolver) recvLoop(conn client.HyUDPConn) {
	for {
		data, _, err := conn.Receive()
		if err != nil {
			r.dropConn(conn)
			return
		}
		if len(data) < 2 {
			continue
		}
		txID := binary.BigEndian.Uint16(data[:2])
		r.mu.Lock()
		var ch chan []byte
		if r.conn == conn {
			ch = r.inflight[txID]
		}
		r.mu.Unlock()
		if ch == nil {
			continue
		}
		select {
		case ch <- data:
		default:
		}
	}
}

func (r *udpResolver) dropConn(conn client.HyUDPConn) {
	r.mu.Lock()
	if r.conn != conn {
		r.mu.Unlock()
		_ = conn.Close()
		return
	}
	r.conn = nil
	pending := r.inflight
	r.inflight = map[uint16]chan []byte{}
	r.mu.Unlock()
	_ = conn.Close()
	for _, ch := range pending {
		close(ch)
	}
}

func (r *udpResolver) register(conn client.HyUDPConn) (chan []byte, uint16, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.conn != conn {
		return nil, 0, errUDPSessionClosed
	}
	if len(r.inflight) >= 0xffff {
		return nil, 0, errors.New("too many in-flight DNS queries")
	}
	var txID uint16
	for {
		txID = uint16(rand.Intn(0x10000))
		if _, taken := r.inflight[txID]; !taken {
			break
		}
	}
	ch := make(chan []byte, 1)
	r.inflight[txID] = ch
	return ch, txID, nil
}

func (r *udpResolver) unregister(txID uint16, ch chan []byte) {
	r.mu.Lock()
	if cur, ok := r.inflight[txID]; ok && cur == ch {
		delete(r.inflight, txID)
	}
	r.mu.Unlock()
}

func (r *udpResolver) send(conn client.HyUDPConn, q []byte) error {
	r.sendMu.Lock()
	defer r.sendMu.Unlock()
	return conn.Send(q, r.server)
}

func (r *udpResolver) isTCPOnly() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.tcpOnly
}

func (r *udpResolver) markTCPOnly() {
	r.mu.Lock()
	r.tcpOnly = true
	r.mu.Unlock()
}

var udpDisabledLimiter = newRateLimiter(30 * time.Second)
